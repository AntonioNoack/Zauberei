package me.anno.zauber.ast.rich.expression

import me.anno.zauber.SpecialFieldNames.OUTER_FIELD_NAME
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.SuperExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.*
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.ASTSimplifier.reorderParameters
import me.anno.zauber.ast.simple.ASTSimplifier.reorderResolveParameters
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ExtensionThis
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauber.typeresolution.TypeResolution.typeToScope
import me.anno.zauber.typeresolution.members.ResolvedConstructor
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.LambdaType

/**
 * Calls base[.name]<typeParams>(valueParams)
 * */
abstract class CallExpressionBase(
    val self: Expression,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    companion object {
        private val LOGGER = LogManager.getLogger(CallExpressionBase::class)
    }

    override fun isResolved(): Boolean = false

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        val contextI = context
            .withTargetType(null /* unknown */)
        if (self.hasLambdaOrUnknownGenericsType(contextI) ||
            valueParameters.any { valueParameter ->
                valueParameter.value.hasLambdaOrUnknownGenericsType(contextI)
            }
        ) return true

        if (typeParameters != null) return false

        try {
            return when (val resolved = resolveCallable(context).resolved) {
                is Method, is Constructor -> resolved.hasUnderdefinedGenerics
                is Field -> true // todo this must be some fun interface -> check whether it has underdefined generics
                else -> throw NotImplementedError("Has $resolved underdefined generics?")
            }
        } catch (e: IllegalStateException) {
            // this can fail, because some values may still be unknown
            e.printStackTrace()
            LOGGER.warn("Failed in hasLambdaOrUnknownGenericsType: ${e.message}")
            // we cannot be sure, better be safe
            return true
        }
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        return resolveCallable(context).resolveValueType()
    }

    abstract fun resolveCallable(context: ResolutionContext): ResolvedMember<*>

    private fun Expression.isLambdaLike(): Boolean {
        return when (this) {
            is LambdaExpression, is DoubleColonLambda,
            is GetMethodFromTypeExpression, is GetMethodFromValueExpression -> true
            else -> false
        }
    }

    private fun resolveInlineMethod(
        context: ResolutionContext, callable: ResolvedMethod,
        params0: List<Expression>
    ): Expression {

        val context = context.withSpec(context.specialization + callable.specialization)
        println("Resolving inline method: $callable, $params0, $context")

        // define all parameters, that are not lambda-likes
        val method = callable.resolved
        val subscope = scope.generate("inline", ScopeType.METHOD_BODY)

        // todo define 'this' field/parameter in all relevant scopes
        // todo handle 'this' like any other field, and allow labels on any field to denote the scope
        // todo create sub-scope for this, and define all parameters as fields of that sub-scope,
        //  because usually, they would be in the method scope, but here, we don't use that scope
        // todo we must recursively support this replacement, e.g. for inline methods with default parameters
        //  -> register these special lambdas in the context :)

        val body = ArrayList<Expression>()
        val knownLambdas = HashMap(context.knownLambdas)
        val inlineBody = method.body
            ?: error(
                "Inline method must have a body, method: $method, " +
                        "at ${resolveOrigin(origin)}"
            )

        for (i in params0.indices) {
            val param = params0[i]
            val dstField = method.valueParameters[i].field!!
            if (!param.isLambdaLike()) {
                val dstFieldExpr = FieldExpression(dstField, subscope, origin)
                body.add(AssignmentExpression(dstFieldExpr, param))
                subscope.addField(dstField)
            } else {
                println("KnownLambdas[$dstField] = $param")
                knownLambdas[dstField] = param
            }
        }

        body.add(inlineBody)

        val extendedContext = context.withKnownLambdas(knownLambdas)

        // todo if lambdaType has a 'this'/when we have some sort of 'this',
        //  we need to define it as a variable/field...
        println("Inlined body:\n${body.joinToString("\n") { "  $it" }}")

        return ExpressionList(body, scope, origin).resolve(extendedContext)

    }

    private fun resolveInlineInvocation(
        context: ResolutionContext, callable: ResolvedField,
        inlineBody: Expression
    ): Expression {

        // println("Resolving inline invocation: $callable, $inlineBody")

        val parameter = callable.resolved.byParameter as? Parameter
            ?: error(
                "Expected field by lambda to belong to a parameter, " +
                        "field: $callable, at ${resolveOrigin(origin)}"
            )
        val parameterType = parameter.type as LambdaType
        val subscope = scope.generate("inline", ScopeType.METHOD_BODY)
        val body = ArrayList<Expression>()
        when (inlineBody) {
            is LambdaExpression -> {
                val selfType = parameterType.selfType?.specialize(context)
                var subContext = context
                if (selfType != null) {
                    val baseField = subscope.createImmutableField(self, "lambdaBase", origin).apply {
                        valueType = selfType
                    }
                    val selfScope = typeToScope(selfType)
                    subContext = subContext.addExtensionThis(ExtensionThis(selfType, selfScope, baseField))
                    val baseFieldExpr = FieldExpression(baseField, baseField.ownerScope, origin)
                    body.add(AssignmentExpression(baseFieldExpr, self))
                }

                for (i in parameterType.parameters.indices) {
                    val param = valueParameters[i].value
                    val variables = inlineBody.variables
                        ?: run {
                            check(parameterType.parameters.size == 1) {
                                "Can only generate helper parameter if there is exactly one"
                            }
                            val field = subscope.addField(
                                null, false, false, null,
                                "it", parameterType.parameters[i].type, null,
                                Flags.NONE, origin
                            )
                            listOf(LambdaVariable(null, field, origin))
                        }

                    val variable = variables[i]
                    val dstField = variable.field
                    if (dstField.name == "_") continue // assignment can be skipped

                    val dstFieldExpr = FieldExpression(dstField, subscope, dstField.origin)
                    body.add(AssignmentExpression(dstFieldExpr, param))
                    subscope.addField(dstField)

                    // we could run and support this recursively :3
                    //  -> I think we kind of already do support recursion :)
                    // todo test recursion for inlined functions
                    if (variable is LambdaDestructuring) {
                        // we must also assign all the child fields
                        val components = variable.components
                        for (i in components.indices) {
                            val component = components[i]
                            if (component.name == "_") continue

                            val dstField = component.field
                            val dstFieldExpr = FieldExpression(dstField, subscope, dstField.origin)
                            val param = NamedCallExpression(dstFieldExpr, componentNames[i], subscope, dstField.origin)
                            body.add(AssignmentExpression(dstFieldExpr, param))
                            subscope.addField(dstField)
                        }
                    }
                }
                body.add(inlineBody.body.resolve(subContext))
            }
            else -> throw NotImplementedError("Implement inlining a call for a lambda-like: $inlineBody (${inlineBody.javaClass.simpleName})")
        }
        return ExpressionList(body, subscope, origin).resolve(context)
    }

    override fun resolveImpl(context: ResolutionContext): Expression {
        return when (val callable = resolveCallable(context)) {
            is ResolvedMethod -> {
                val method = callable.resolved
                val shouldBeInlined = method.isInline()
                // we can only inline, if some or our parameters are lambdas
                //  or lambda-likes... (Type::add) should work, too
                //  -> no, we can always inline :)
                if (shouldBeInlined) {
                    val params0 = reorderParameters(valueParameters, method.valueParameters, scope, origin)
                        .applyImplicitCasts(method.valueParameters, context)
                    resolveInlineMethod(context, callable, params0)
                } else {
                    // todo base must be defined, so resolve instance/this
                    val selfExpr = if (
                        (self !is FieldExpression && self !is UnresolvedFieldExpression) ||
                        this is NamedCallExpression
                    ) {
                        self.resolve(context)
                    } else null // else base was consumed to be the method

                    // println("Resolved $self (${self.javaClass.simpleName}) to $base")

                    // println("base for call: $method, base: $base, this.base: ${this.base}")
                    val targetParams = method.valueParameters
                    val paramContext = context.withSpec(context.specialization + callable.specialization)
                    // println("Base for $this: $base, targetParams: $targetParams, ctx: $paramContext")
                    val params = reorderResolveParameters(paramContext, valueParameters, targetParams, scope, origin)
                        .applyImplicitCasts(method.valueParameters, context)
                    val thisExpr = if (callable.resolved.hasExplicitSelfType) {
                        // todo check that 'this' is accessible from 'scope'
                        ThisExpression(callable.resolved.ownerScope, scope, origin)
                    } else null

                    ResolvedCallExpression(selfExpr, thisExpr, callable, params, scope, origin)
                }
            }
            is ResolvedConstructor -> {
                var valueParams = valueParameters
                val createdType = callable.resolved.selfTypeI
                if (createdType.clazz.scopeType == ScopeType.INNER_CLASS) {
                    // println("self for constructor: $self for ${callable.resolved}")
                    val outerSelfParam = NamedParameter(OUTER_FIELD_NAME, self)
                    valueParams = listOf(outerSelfParam) + valueParams
                }

                val targetParams = callable.resolved.valueParameters
                val params = reorderResolveParameters(context, valueParams, targetParams, scope, origin)
                ResolvedCallExpression(self as? SuperExpression, null, callable, params, scope, origin)
            }
            is ResolvedField -> {
                val inlineBody = context.knownLambdas[callable.resolved]
                if (inlineBody != null) {
                    return resolveInlineInvocation(context, callable, inlineBody)
                }

                val base = self.resolve(context)
                val valueParameters1 = resolveValueParameters(context, valueParameters, null)
                // for (vp in valueParameters1) checkTypeMakesSense((vp as? ValueParameterImpl)?.type, scope)

                println("Resolved value parameters: $valueParameters -> $valueParameters1")

                val calledMethod = callable.resolveCalledMethod(typeParameters, valueParameters1)
                val targetParams = calledMethod.resolved.valueParameters
                val params = reorderResolveParameters(context, valueParameters, targetParams, scope, origin)
                    .applyImplicitCasts(targetParams, context)
                val base1 = ResolvedGetFieldExpression(base, callable, scope, origin)
                ResolvedCallExpression(base1, null, calledMethod, params, scope, origin)
            }
            else -> throw NotImplementedError()
        }
    }

    fun List<Expression>.applyImplicitCasts(params: List<Parameter>, context: ResolutionContext): List<Expression> {
        return mapIndexed { index, expression ->
            val type = params[index].type.specialize(context)
            expression.implicitCastTo(params[index], context.withTargetType(type))
        }
    }

    fun checkTypeMakesSense(type: Type?, scope: Scope) {
        // println("checkTypeMakesSense($type, ${type?.javaClass?.simpleName}, ${scope.pathStr})")
        when (type) {
            null -> return
            is ClassType -> {
                val tp = type.typeParameters ?: return
                for (tpi in tp) checkTypeMakesSense(tpi, scope)
            }
            is GenericType -> {
                val targetScope = type.scope
                var scopeI = scope
                // scope must be available/visible from us...
                while (true) {
                    if (scopeI == targetScope) return // all fine
                    scopeI = scopeI.parentIfSameFileAndVisible ?: break
                }
                error("$type is not defined in $scope")
            }
            else -> TODO("Is $type (${type.javaClass.simpleName}) generic?")
        }
    }
}