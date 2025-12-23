package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.ResolveField.findFieldType
import me.anno.zauberei.typeresolution.ResolveMethod.resolveCallType
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class NamedCallExpression(
    val base: Expression,
    val name: String,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    scope: Scope, origin: Int
) : Expression(scope, origin) {

    init {
        if (name == "." && valueParameters.size == 1 &&
            valueParameters[0].value is NamedCallExpression
        ) throw IllegalStateException("NamedCall-stack must be within base, not in parameter: $this")
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in valueParameters.indices) {
            callback(valueParameters[i].value)
        }
    }

    override fun clone() = NamedCallExpression(
        base.clone(), name, typeParameters,
        valueParameters.map { NamedParameter(it.name, it.value.clone()) },
        scope, origin
    )

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return typeParameters == null ||
                base.hasLambdaOrUnknownGenericsType() ||
                valueParameters.any { it.value.hasLambdaOrUnknownGenericsType() }
    }

    override fun toString(): String {
        return when {
            typeParameters.isNullOrEmpty() && name == "." &&
                    valueParameters.size == 1 &&
                    when (valueParameters[0].value) {
                        is NameExpression,
                        is CallExpression,
                        is NamedCallExpression -> true
                        else -> false
                    } -> {
                if (base is NameExpression) {
                    "$base.${valueParameters[0].value}"
                } else {
                    "($base).${valueParameters[0].value}"
                }
            }
            typeParameters != null && typeParameters.isEmpty() -> {
                "($base).$name(${valueParameters.joinToString()})"
            }
            else -> {
                "($base).$name<${typeParameters?.joinToString() ?: "?"}>(${valueParameters.joinToString()})"
            }
        }
    }

    override fun resolveType(context: ResolutionContext): Type {
        val baseType = TypeResolution.resolveType(
            /* targetLambdaType seems not easily deductible */
            context.withTargetType(null),
            base,
        )
        if (name == ".") {
            check(valueParameters.size == 1)
            val parameter0 = valueParameters[0]
            check(parameter0.name == null)
            when (val parameter = parameter0.value) {
                is NameExpression -> {
                    val fieldName = parameter.name
                    return findFieldType(
                        baseType, fieldName, emptyList(),
                        parameter.scope, parameter.origin, context.targetType
                    ) ?: throw IllegalStateException("Missing $baseType.$fieldName in ${resolveOrigin(origin)}")
                }
                is CallExpression -> {
                    val baseName = parameter.base as NameExpression
                    val constructor = null
                    // todo for lambdas, baseType must be known for their type to be resolved
                    val valueParameters = resolveValueParameters(context, parameter.valueParameters)
                    return resolveCallType(
                        context.withSelfType(baseType),
                        this, baseName.name, constructor,
                        parameter.typeParameters, valueParameters
                    )
                }
                else -> TODO("dot-operator with $parameter in ${resolveOrigin(origin)}")
            }
        } else {

            val calleeType = TypeResolution.resolveType(
                /* target lambda type seems not deductible */
                context.withTargetType(null),
                base,
            )
            // todo type-args may be needed for type resolution
            val valueParameters = resolveValueParameters(context, valueParameters)

            val constructor = null
            return resolveCallType(
                context.withSelfType(calleeType),
                this, name, constructor,
                typeParameters, valueParameters
            )
        }
    }
}