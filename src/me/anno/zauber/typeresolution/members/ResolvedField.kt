package me.anno.zauber.typeresolution.members

import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.controlflow.IfElseBranch
import me.anno.zauber.ast.rich.expression.CheckEqualsOp
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.IsInstanceOfExpr
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.*
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ParameterList.Companion.resolveGenerics
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.typeresolution.TypeResolution.typeToScope
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.typeresolution.members.FieldResolver.resolveField
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.getScope
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.LambdaType
import me.anno.zauber.types.impl.LambdaType.Companion.getLambdaTypeName
import me.anno.zauber.types.impl.arithmetic.AndType.Companion.andTypes
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnknownType

// todo we don't need only the type-param-generics, but also the self-type generics...
class ResolvedField(
    field: Field,
    context: ResolutionContext, codeScope: Scope,
    matchScore: MatchScore
) : ResolvedMember<Field>(field, context, codeScope, matchScore) {

    companion object {

        private val LOGGER = LogManager.getLogger(ResolvedField::class)

        fun filterTypeByScopeConditions(field: Field, type: Type, context: ResolutionContext, codeScope: Scope): Type {
            // todo filter type based on scope conditions
            // todo branches that return Nothing shall be ignored, and their condition applies even after
            var type = type
            var scope = codeScope

            if (LOGGER.isInfoEnabled) LOGGER.info("Filtering $type for $scope")

            while (true) {
                val conditions = scope.branchConditions
                for (i in conditions.indices) {
                    val condition = conditions[i]
                    if (LOGGER.isInfoEnabled) LOGGER.info("Filtering $type for $scope by $condition")
                    type = applyConditionToType(field, type, condition, context)
                }

                // if (LOGGER.isInfoEnabled) LOGGER.info("Scope-Condition[${scope.pathStr}]: $conditions")
                scope = scope.parentIfSameFile ?: break
            }
            return type
        }

        fun applyConditionToType(field: Field, type: Type, expr: Expression, context: ResolutionContext): Type {
            return when (expr) {
                is IsInstanceOfExpr -> {
                    if (exprIsField(field, expr.value, context)) {
                        andTypes(expr.type, type)
                    } else type
                }
                is CheckEqualsOp -> {
                    val newType = when {
                        exprIsField(field, expr.left, context) -> getUniqueValueType(context, expr.right)
                        exprIsField(field, expr.right, context) -> getUniqueValueType(context, expr.left)
                        else -> null
                    }
                    if (newType != null) {
                        println("applying newType: $newType onto $type")
                        val newType2 = if (expr.negated) {
                            newType.not()
                        } else newType
                        andTypes(type, newType2)
                    } else type
                }
                else -> {
                    LOGGER.info("!Ignoring $expr for $field")
                    type
                }
            }
        }

        fun exprIsField(field: Field, expr: Expression, context: ResolutionContext): Boolean {
            return when (expr) {
                is MemberNameExpression -> {
                    if (expr.name == field.name) {
                        val field2 = resolveField(context, expr.scope, expr.name, expr.nameAsImport, null, expr.origin)
                        field2?.resolved == field
                    } else false
                }
                is UnresolvedFieldExpression -> {
                    if (expr.name == field.name) {
                        val field2 = resolveField(context, expr.scope, expr.name, expr.nameAsImport, null, expr.origin)
                        field2?.resolved == field
                    } else false
                }
                is FieldExpression -> expr.field == field
                is ResolvedFieldExpression -> expr.field.resolved == field
                is NamedCallExpression,
                is CallExpression,
                is ResolvedCallExpression,
                is SuperCallExpression,
                is SpecialValueExpression -> false
                is ExpressionList -> exprIsField(field, expr.list.last(), context)
                is IfElseBranch -> expr.elseBranch != null && // unlikely
                        exprIsField(field, expr.ifBranch, context) &&
                        exprIsField(field, expr.elseBranch, context)
                is NumberExpression, is StringExpression -> false
                is DotExpression -> {
                    val baseType = expr.getBaseType(context)
                    val field = expr.resolveField(context, baseType)
                    field?.resolved == field
                }
                else -> throw NotImplementedError("Is $expr (${expr.javaClass.simpleName}) the same as $field?")
            }
        }

        private val uvStack = ArrayList<Expression>()

        /**
         * If value == expr, then value must have a special type:
         * */
        fun getUniqueValueType(context: ResolutionContext, expr: Expression): Type? {
            if (expr in uvStack) return null
            uvStack.add(expr)
            val type = TypeResolution.resolveType(context, expr).resolvedName
            uvStack.removeLast()
            return if (hasOnlyOneValue(type)) type else null
        }

        fun hasOnlyOneValue(type: Type): Boolean {
            val type = type
            if (type == NullType || type == UnknownType) return true
            if (type is ClassType && type.clazz.isEnumEntry()) return true
            if (type is ClassType && type.clazz.isEnumClass()) return type.clazz.enumEntries.size < 2
            return false
        }

        fun resolveFunInterfaceType(type: Type): ClassType {
            val scope = typeToScope(type)
                ?: error("Cannot resolve scope from $type for fun-interface")
            val funScope = resolveFunInterfaceType(scope)
                ?: error("Could not find fun-interface in $scope")
            return funScope.typeWithArgs2
        }

        // todo we should also support multiple interfaces, and choose the best match
        fun resolveFunInterfaceType(scope: Scope): Scope? {
            if (scope.flags.hasFlag(Flags.FUN_INTERFACE)) {
                return scope
            }
            for (superCall in scope.superCalls) {
                val byCall = resolveFunInterfaceType(superCall.type.clazz)
                if (byCall != null) return byCall
            }
            return null
        }
    }

    init {
        check(context.specialization.scope == field.fieldScope)
    }

    val isBackingField = field.isBackingField()

    override fun getScopeOfResolved(): Scope = resolved.scope

    val isMutable: Boolean
        get() = resolved.isMutableEx

    fun getValueType(): Type {
        if (LOGGER.isInfoEnabled) LOGGER.info("Getting type of $resolved in scope ${codeScope.pathStr}, selfType: $selfType")

        val valueType = resolved.resolveValueType(context)
        val valueTypeSpec = specialization.typeParameters.resolveGenerics(selfType, valueType)

        val context = context.withSelfType(selfType)
        return filterTypeByScopeConditions(resolved, valueTypeSpec, context, codeScope)
    }

    override fun resolveValueType(): Type {
        val baseType = getValueType()
        if (baseType is LambdaType) {
            return baseType.returnType // easy-peasy :3
        }

        val funInterfaceType = resolveFunInterfaceType(baseType)
        val methods = funInterfaceType.clazz.getMethods(ScopeInitType.AFTER_DISCOVERY)
        check(methods.size == 1) {
            "Fun interfaces should only have one method, $funInterfaceType has $methods"
        }
        // todo having the parameters here would be really helpful for resolving the type...
        val method = methods.first()
        return method.resolveReturnType(context)
        // this must be a fun-interface, and we need to get the return type of the call...
        //  luckily, there is only a single method, but unfortunately, we need the call parameters...
    }

    override fun resolveThrownType(): Type {
        LOGGER.warn("Todo: Implement resolveThrownType for field call")
        return Types.Throwable
    }

    override fun resolveYieldedType(): Type {
        LOGGER.warn("Todo: Implement resolveThrownType for field call")
        return Types.Yielded
    }

    override fun toString(): String {
        return "Resolved${resolved.toStringWithoutDefault()}${specialization.toStringWithoutScope()}"
    }

    fun resolveCalledMethod(typeParameters: List<Type>?, valueParameters: List<ValueParameter>): ResolvedMethod {
        val baseType = getValueType()
        if (baseType is LambdaType) {
            val numArguments = baseType.parameters.size
            val lambdaClassName = getLambdaTypeName(numArguments)
            val genericNames = String(CharArray(numArguments + 1) { 'A' + it })

            val nat = Types.NullableAny
            val lambdaClassScope = getScope(lambdaClassName, genericNames, nat)

            val method = lambdaClassScope.getMethods(ScopeInitType.AFTER_DISCOVERY)
                .firstOrNull { it.name == "call" && it.typeParameters.isEmpty() && it.valueParameters.size == numArguments }
                ?: error("Fun-Interface $lambdaClassName is missing .call() method")

            val scopeSelfType = lambdaClassScope.typeWithArgs
            val methodReturnType = baseType.returnType

            /*println("Finding method match:")
            println("  method: $method -> $methodReturnType")
            println("  targetType: ${context.targetType}")
            println("  selfType: $scopeSelfType")
            println("  typeParameters: $typeParameters")
            println("  valueParameters: $valueParameters")
            println("  lambda-params: ${baseType.parameters}")
            println("  lambda-returnType: ${baseType.returnType}")*/

            return FindMemberMatch.findMemberMatch(
                method, methodReturnType, context.targetType, scopeSelfType,
                typeParameters, valueParameters, specialization, codeScope, resolved.origin
            ) as? ResolvedMethod
                ?: error("Failed to resolve fun-interface on lambda, $lambdaClassName (${valueParameters.size})")
        }

        TODO("Resolve type parameters for $baseType call on a function interface")
    }

    fun resolveCopyMethod(): ResolvedMethod {
        val field = resolved
        val valueScope = field.ownerScope[ScopeInitType.AFTER_OVERRIDES]
        val method = valueScope.methods.firstOrNull {
            it.name == "copy" &&
                    it.valueParameters.size == 1 &&
                    it.valueParameters[0].run { name == field.name && type == field.valueType }
        } ?: error("Missing copy method for $field, candidates: ${valueScope.methods}")
        return ResolvedMethod(
            method, context.withSpec(Specialization(method.scope, specialization.typeParameters)),
            codeScope, matchScore
        )
    }

    val ownerScope get() = resolved.scope

    fun resolveOwnerWithoutLeftSide(scope: Scope, origin: Long): Expression {
        // println("Returning this for $resolved in $scope")
        return ThisExpression(ownerScope, scope, origin)
    }
}