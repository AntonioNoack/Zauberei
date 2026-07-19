package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.TypeExpression
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Import
import me.anno.zauber.types.LambdaParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.LambdaType
import me.anno.zauber.types.impl.NonObjectClassType

/**
 * Generates a lambda from the base, effectively being a::b -> { a.b(allParamsNeeded) }
 * */
class GetMethodFromValueExpression(
    val self: Expression,
    val name: String,
    val nameAsImport: List<Import>,
    origin: Long
) : Expression(self.scope, origin) {

    companion object {
        private val LOGGER = LogManager.getLogger(GetMethodFromValueExpression::class)
    }

    override fun toStringImpl(depth: Int): String {
        return "${self.toString(depth)}::$name"
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        val targetType = context.targetType
        if (targetType is LambdaType) {
            return targetType
        }

        // quick and dirty fallback:
        return resolveImpl(context).resolveValueType(context)
    }

    override fun resolveImpl(context: ResolutionContext): Expression {
        val targetType = context.targetType
        if (targetType is LambdaType) {
            when (self) {
                is TypeExpression -> return resolveFromType(context, targetType, self.type)
                is UnresolvedFieldExpression -> {
                    // check if it is a type
                    val selfType = self.scope.resolveTypeOrNull(self.name, self.nameAsImport)
                    if (selfType != null) {
                        return resolveFromType(context, targetType, selfType)
                    } else {
                        val field = self.resolveField(context.withTargetType(null))
                            ?: self.onMissingField()
                        TODO("ResolveImpl($this, $context, ${self.javaClass.simpleName}, field $field)")
                    }
                }
                else -> {
                    TODO("ResolveImpl($this, $context, ${self.javaClass.simpleName})")
                }
            }
        }

        // todo maybe we have luck, and there is only one candidate? if so, use that...
        //  Object::method is ambiguous: is self part of the lambda-type?

        // todo this itself is also ambiguous: Int::plus matches Int.(Int) -> Int and (Int,Int)->Int

        val valueType = self.resolveValueType(context)
        if (valueType is NonObjectClassType) {
            // todo check visibility... private methods may only be resolved from inside this class...
            val methods = valueType.type.clazz[ScopeInitType.AFTER_OVERRIDES].methods
                .filter { it.name == name }
            check(methods.isNotEmpty()) { "Failed to resolve $valueType::$name at ${resolveOrigin(origin)}" }
            check(methods.size == 1) {
                "Call to $valueType::$name is ambiguous, found multiple candidates: $methods, " +
                        "at ${resolveOrigin(origin)}"
            }
            val method = methods.first()
            val lambdaType = LambdaType(
                valueType.type,
                method.valueParameters.map { LambdaParameter(it.name, it.type, it.origin) },
                method.resolveReturnType(context)
            )
            LOGGER.info("Trying to resolve $this using $lambdaType")
            return resolveFromType(context, lambdaType, valueType.type)
        }

        TODO("ResolveImpl($this, $context), tt: $targetType, vt: $valueType")
    }

    private fun resolveFromType(context: ResolutionContext, targetType: LambdaType, selfType: Type): Expression {
        val isObject = selfType is ClassType && selfType.clazz.isObjectLike()
        if (isObject) {
            val tmpScope = scope.generate("lambda", ScopeType.LAMBDA)
            val variables = List(targetType.parameters.size) {
                val type = targetType.parameters[it].type
                val name = ('a' + it).toString()
                val field = tmpScope.addField(
                    null, false, isMutable = false, null,
                    name, type, null, Flags.NONE, origin
                )
                LambdaVariable(type, field, origin)
            }


            val base = TypeExpression(selfType, scope, origin)
            val valueParameters = variables.map {
                NamedParameter(FieldExpression(it.field, scope, origin))
            }
            val expr = LambdaExpression(
                variables, tmpScope,
                NamedCallExpression(base, name, nameAsImport, null, valueParameters, scope, origin)
            )
            tmpScope.selfAsLambda = expr
            return expr.resolve(context)
        } else {
            check(targetType.parameters.isNotEmpty()) {
                "Expected at least one parameter to generate $this for $targetType"
            }

            println("lambda-targetType: $targetType, spec: ${context.specialization}")
            val tmpScope = scope.generate("lambda", ScopeType.LAMBDA)
            val variables = List(targetType.parameters.size) {
                val type = targetType.parameters[it].type.specialize(context)
                println("lambda-specialized ${targetType.parameters[it].type} -> $type for $this, $context")
                val field = tmpScope.addField(
                    null, false, isMutable = false, null,
                    name, type, null, Flags.NONE, origin
                )
                LambdaVariable(type, field, origin)
            }
            val base = FieldExpression(variables[0].field, tmpScope, origin)
            val valueParameters = variables.subList(1, variables.size).map {
                NamedParameter( FieldExpression(it.field, it.field.fieldScope, origin))
            }
            val expr = LambdaExpression(
                variables, tmpScope,
                NamedCallExpression(base, name, nameAsImport, null, valueParameters, scope, origin)
            )
            tmpScope.selfAsLambda = expr
            return expr.resolve(context)
        }
    }

    override fun resolveThrownType(context: ResolutionContext): Type = Types.Nothing
    override fun resolveYieldedType(context: ResolutionContext): Type = Types.Nothing

    // todo or if the resolved method has some...
    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        return true // base.hasLambdaOrUnknownGenericsType()
    }

    override fun needsBackingField(methodScope: Scope): Boolean = self.needsBackingField(methodScope)
    override fun splitsScope(): Boolean = false
    override fun isResolved(): Boolean = false

    override fun clone(scope: Scope) = GetMethodFromValueExpression(self.clone(scope), name, nameAsImport, origin)
    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(self)
    }

}