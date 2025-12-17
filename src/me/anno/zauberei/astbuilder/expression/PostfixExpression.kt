package me.anno.zauberei.astbuilder.expression

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
}