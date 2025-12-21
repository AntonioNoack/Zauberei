package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.typeresolution.TypeResolution.removeNullFromType
import me.anno.zauberei.types.Type

enum class PostfixType(val symbol: String) {
    INCREMENT("++"),
    DECREMENT("--"),
    ASSERT_NON_NULL("!!")
}

class PostfixExpression(val base: Expression, val type: PostfixType, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$base${type.symbol}"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return when (type) {
            PostfixType.ASSERT_NON_NULL -> {
                val type = TypeResolution.resolveType(
                    /* just copying targetLambdaType is fine, is it? */
                    context, base,
                )
                removeNullFromType(type)
            }
            else -> TODO("Resolve type for PostfixExpression.${type} in ${context.codeScope}, ${context.selfType}")
        }
    }
}