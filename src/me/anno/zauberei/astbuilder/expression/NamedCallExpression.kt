package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.typeresolution.TypeResolution.findFieldType
import me.anno.zauberei.typeresolution.TypeResolution.resolveCallType
import me.anno.zauberei.typeresolution.TypeResolution.resolveValueParams
import me.anno.zauberei.types.Type

class NamedCallExpression(
    val base: Expression,
    val name: String,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    origin: Int
) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in valueParameters.indices) {
            callback(valueParameters[i].value)
        }
    }

    override fun toString(): String {
        return when {
            typeParameters.isNullOrEmpty() && name == "." &&
                    valueParameters.size == 1 &&
                    when (valueParameters[0].value) {
                        is VariableExpression,
                        is CallExpression,
                        is NamedCallExpression -> true
                        else -> false
                    } -> {
                if (base is VariableExpression) {
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
            /* targetLambdaType seems not deductible */
            context.withTargetType(null),
            base,
        )
        if (name == ".") {
            check(valueParameters.size == 1)
            val parameter0 = valueParameters[0]
            check(parameter0.name == null)
            when (val parameter = parameter0.value) {
                is VariableExpression -> {
                    val fieldName = parameter.name
                    return findFieldType(baseType, fieldName)
                        ?: throw IllegalStateException("Missing $baseType.$fieldName")
                }
                is CallExpression -> {
                    val baseName = parameter.base as VariableExpression
                    val constructor = null
                    // todo for lambdas, baseType must be known for their type to be resolved
                    val valueParameters = resolveValueParams(context, parameter.valueParameters)
                    return resolveCallType(
                        context.withSelfType(baseType),
                        this, baseName.name, constructor,
                        parameter.typeParameters, valueParameters
                    )
                }
                else -> TODO("dot-operator with $parameter")
            }
        } else {

            val calleeType = TypeResolution.resolveType(
                /* target lambda type seems not deductible */
                context.withTargetType(null),
                base,
            )
            // todo type-args may be needed for type resolution
            val valueParameters = resolveValueParams(context, valueParameters)

            val constructor = null
            return resolveCallType(
                context.withSelfType(calleeType),
                this, name, constructor,
                typeParameters, valueParameters
            )
        }
    }
}