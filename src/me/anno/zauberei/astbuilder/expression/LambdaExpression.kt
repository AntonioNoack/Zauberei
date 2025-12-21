package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.LambdaParameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.LambdaType

class LambdaExpression(
    var variables: List<LambdaVariable>?,
    val body: Expression,
    val scope: Scope,
) : Expression(body.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(body)
    }

    override fun toString(): String {
        return "LambdaExpr(${variables ?: "?"} -> $body)"
    }

    override fun resolveType(context: ResolutionContext): Type {
        println("Handling lambda expression... target: ${context.targetType}")
        when (val targetLambdaType = context.targetType) {
            is LambdaType -> {
                // automatically add it...
                if (variables == null) {
                    variables = when (val size = targetLambdaType.parameters.size) {
                        0 -> emptyList()
                        1 -> {
                            // define 'it'-parameter in the scope
                            val autoParamName = "it"
                            println("Inserting $autoParamName into lambda automatically")
                            Field(
                                scope, false, true, null,
                                autoParamName, null, null,
                                emptyList(), origin
                            )
                            listOf(LambdaVariable(null, autoParamName))
                        }
                        else -> {
                            // instead of throwing, we should probably just return some impossible type or error type...
                            throw IllegalStateException("Found lambda without parameters, but expected $size")
                        }
                    }
                }

                check(variables?.size == targetLambdaType.parameters.size)

                val resolvedReturnType = /*resolveTypeGivenGenerics(
                            targetLambdaType.returnType,
                            targetLambdaType.parameters,
                            generics,
                        )*/ targetLambdaType.returnType
                val parameters = variables!!.mapIndexed { index, param ->
                    val type = param.type ?: targetLambdaType.parameters[index].type
                    LambdaParameter(param.name, type)
                }
                return LambdaType(parameters, resolvedReturnType)
            }
            null -> {
                // else 'it' is not defined
                if (variables == null) variables = emptyList()

                val returnType = TypeResolution.resolveType(
                    context.withTargetType(null),
                    body,
                )

                return LambdaType(variables!!.map {
                    LambdaParameter(it.name, it.type!!)
                }, returnType)
            }
            else -> throw NotImplementedError("Extract LambdaType from $targetLambdaType")
        }
    }
}