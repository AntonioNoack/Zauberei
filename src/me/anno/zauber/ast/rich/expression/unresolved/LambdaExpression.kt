package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.ParameterMutability
import me.anno.zauber.ast.rich.parameter.ParameterType
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.typeresolution.TypeResolution.typeToScope
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedConstructor
import me.anno.zauber.types.LambdaParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.LambdaType
import me.anno.zauber.types.impl.LambdaType.Companion.getLambdaTypeName

class LambdaExpression(
    var variables: List<LambdaVariable>?,
    val bodyScope: Scope,
    val body: Expression,
) : Expression(bodyScope, body.origin) {

    companion object {
        private val LOGGER = LogManager.getLogger(LambdaExpression::class)
    }

    init {
        // may override the original -> a little unsafe...
        check(bodyScope.scopeType == ScopeType.LAMBDA)
        bodyScope.selfAsLambda = this
    }

    override fun toStringImpl(depth: Int): String {
        return "LambdaExpr(${variables ?: "?"} -> ${body.toString(depth)})"
    }

    // scope cannot be changed that easily, like with branches and loops
    override fun clone(scope: Scope) = LambdaExpression(variables, bodyScope, body.clone(bodyScope))

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = true
    override fun needsBackingField(methodScope: Scope): Boolean = body.needsBackingField(methodScope)
    override fun isResolved(): Boolean = false
    override fun splitsScope(): Boolean = false // I don't think so

    override fun resolveValueType(context: ResolutionContext): LambdaType {
        LOGGER.info("Handling lambda expression... target: ${context.targetType}")

        val bodyContext = context
            .withTargetType(null)

        val targetLambdaType = context.targetType
            ?.specialize(context)

        when (targetLambdaType) {
            is LambdaType -> {
                // automatically add it...
                if (variables == null) {
                    variables = when (val size = targetLambdaType.parameters.size) {
                        0 -> emptyList()
                        1 -> {
                            // define 'it'-parameter in the scope
                            val param0 = targetLambdaType.parameters[0]
                            val type = param0.type
                            val autoParamName = "it"
                            LOGGER.info("Inserting $autoParamName into lambda automatically, type: $type")
                            val field = bodyScope.fields.firstOrNull { it.name == autoParamName } ?: bodyScope.addField(
                                null, false, isMutable = false, param0,
                                autoParamName, type, null,
                                Flags.NONE, origin
                            )
                            listOf(LambdaVariable(type, field, origin))
                        }
                        else -> {
                            // instead of throwing, we should probably just return some impossible type or error type...
                            error("Found lambda without parameters, but expected $size")
                        }
                    }
                }

                check(variables?.size == targetLambdaType.parameters.size)

                var bodyContext = bodyContext
                val newScopeType = targetLambdaType.selfType // todo replace generics inside this one?
                if (newScopeType != null) bodyContext = bodyContext.withSelfType(newScopeType)

                val newReturnType = if (targetLambdaType.returnType.containsGenerics()) {
                    // we need to inspect the contents
                    TypeResolution.resolveType(bodyContext, body)
                } else targetLambdaType.returnType // trust-me-bro
                val newParameters = variables!!.mapIndexed { index, param ->
                    val type = param.type ?: targetLambdaType.parameters[index].type
                    LambdaParameter(param.name, type, param.origin)
                }
                return LambdaType(newScopeType, newParameters, newReturnType)
            }
            /*is ClassType, is UnionType -> {
                // is this ok??? OMG, looks like it is fine
                return targetLambdaType
            }*/
            else -> {
                // else 'it' is not defined
                val returnType = TypeResolution.resolveType(bodyContext, body)
                return LambdaType(null, ensureVariables().map {
                    LambdaParameter(it.name, it.type!!, it.origin)
                }, returnType)
            }
            // else -> throw NotImplementedError("Extract LambdaType from $targetLambdaType")
        }
    }

    private fun ensureVariables(): List<LambdaVariable> {
        var variables = variables
        if (variables == null) {
            variables = emptyList()
            this.variables = variables
        }
        return variables
    }

    private fun findSelfType(context: ResolutionContext): Type {
        var selfMethodScope = scope[ScopeInitType.AFTER_DISCOVERY]
        while (true) {
            if (selfMethodScope.isMethodLike() || selfMethodScope.isClassLike()) {
                return selfMethodScope.typeWithArgs
            }

            selfMethodScope = selfMethodScope.parentIfSameFile
                ?: return context.selfType
                    ?: error("Missing method scope for $this by $scope in ${resolveOrigin(origin)}, context: $context")
        }
    }

    override fun resolveImpl(context: ResolutionContext): Expression {

        // todo get or create a custom anonymous class,
        //  - define field for outer scope variable
        //  - super type for invocation
        //  - define constructor
        //  - create instance
        // done
        //  - decide/generate name

        // todo generics are inherited from outer method & class...
        val classScope = scope.generate("lambda", origin, ScopeType.INLINE_CLASS)
        val classConstructor = classScope.getOrCreatePrimaryConstructorScope()

        classScope.setTypeParams(emptyList())

        val lambdaType = resolveValueType(context)
        val lambdaTypeName = getLambdaTypeName(lambdaType.parameters.size)
        val typeParameters0 = lambdaType.parameters.map { it.type }
        val typeParameters = typeParameters0 + lambdaType.returnType
        val superTypeI = ClassType(
            langScope.getOrPut(lambdaTypeName, ScopeType.INTERFACE),
            typeParameters, origin
        )

        classScope.superCalls.clear()
        classScope.superCalls.add(SuperCall(superTypeI, null, null, origin))

        val selfMethodType = findSelfType(context)
        // todo if selfType != null, we need a second self-parameter
        val methodParameter = Parameter(
            0, "__self", ParameterType.VALUE_PARAMETER, ParameterMutability.VAL,
            selfMethodType, classConstructor, origin
        )
        val methodField = classScope.addField(
            null, false, false,
            methodParameter, methodParameter.name, selfMethodType,
            null, Flags.SYNTHETIC, 0,
        )

        val lambdaMethodScope = classScope.getOrPut("call", ScopeType.METHOD)
        lambdaMethodScope.setTypeParams(emptyList())

        val newParameters = lambdaType.parameters.mapIndexed { index, it ->
            Parameter(
                index, it.name ?: "_", ParameterType.VALUE_PARAMETER, ParameterMutability.VAL, it.type,
                lambdaMethodScope, it.origin
            )
        }

        val newParameterFields = newParameters.map { it.getOrCreateField(lambdaType.selfType, Flags.NONE) }
        val oldFields = lambdaType.parameters.mapNotNull { parameter ->
            if (parameter.name != null)
                body.scope.fields.firstOrNull { field -> field.name == parameter.name }
                    ?: throw IllegalStateException(
                        "Missing field '${style(parameter.name, GREEN)}' " +
                                "in ${body.scope}"
                    )
            else null
        }
        assertEquals(oldFields.size, newParameterFields.size)
        val adjustedBody = body.replaceLambdaFieldsWithClassFields(oldFields, newParameterFields)
        lambdaMethodScope.selfAsMethod = Method(
            lambdaType.selfType, lambdaType.selfType != null, "call",
            typeParameters = emptyList(), valueParameters = newParameters,
            lambdaMethodScope, lambdaType.returnType, emptyList(),
            ReturnExpression(adjustedBody, null, scope, origin),
            Flags.SYNTHETIC or Flags.OVERRIDE, origin
        )

        val constructorBody = ArrayList<Expression>()
        val mpf = methodParameter.getOrCreateField(null, Flags.SYNTHETIC)
        constructorBody.add(
            AssignmentExpression(
                DotExpression(
                    ThisExpression(classScope, scope, origin), emptyList(),
                    FieldExpression(methodField, methodField.scope, origin), scope, origin
                ),
                FieldExpression(mpf, classConstructor, origin)
            )
        )
        classConstructor.selfAsConstructor = Constructor(
            listOf(methodParameter),
            classConstructor, null,
            ExpressionList(constructorBody, scope, origin), Flags.SYNTHETIC, origin
        )

        val selfMethod = ThisExpression(typeToScope(selfMethodType)!!, scope, origin)
        val method = ResolvedConstructor(
            classConstructor.selfAsConstructor!!,
            context.withSpec(context.specialization.withScope(classConstructor)),
            scope, MatchScore.zero,
        )
        val params = listOf(selfMethod).map { it.resolve(context) }
        return ResolvedCallExpression(null, null, method, params, scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(body)
    }
}