package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.types.LambdaParameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.LambdaType

class LambdaExpression(
    var variables: List<LambdaVariable>?,
    val bodyScope: Scope,
    val body: Expression,
) : Expression(body.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(body)
    }

    override fun toString(): String {
        return "LambdaExpr(${variables ?: "?"} -> $body)"
    }

    override fun clone() = LambdaExpression(variables, bodyScope, body.clone())

    override fun hasLambdaOrUnknownGenericsType(): Boolean = true

    override fun resolveType(context: ResolutionContext): Type {
        println("Handling lambda expression... target: ${context.targetType}")
        val bodyContext = context
            .withCodeScope(bodyScope)
            .withTargetType(null)
        when (val targetLambdaType = context.targetType) {
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
                            println("Inserting $autoParamName into lambda automatically, type: $type")
                            Field(
                                bodyScope, false, true, null,
                                autoParamName, type, null,
                                emptyList(), origin
                            )
                            listOf(LambdaVariable(type, autoParamName))
                        }
                        else -> {
                            // instead of throwing, we should probably just return some impossible type or error type...
                            throw IllegalStateException("Found lambda without parameters, but expected $size")
                        }
                    }
                }

                check(variables?.size == targetLambdaType.parameters.size)

                val resolvedReturnType = if (targetLambdaType.returnType.containsGenerics()) {
                    // we need to inspect the contents
                    TypeResolution.resolveType(bodyContext, body)
                } else targetLambdaType.returnType // trust-me-bro
                val parameters = variables!!.mapIndexed { index, param ->
                    val type = param.type ?: targetLambdaType.parameters[index].type
                    LambdaParameter(param.name, type)
                }
                return LambdaType(parameters, resolvedReturnType)
            }
            null -> {
                // else 'it' is not defined
                if (variables == null) variables = emptyList()

                val returnType = TypeResolution.resolveType(bodyContext, body,)
                return LambdaType(variables!!.map {
                    LambdaParameter(it.name, it.type!!)
                }, returnType)
            }
            else -> throw NotImplementedError("Extract LambdaType from $targetLambdaType")
        }
    }
}