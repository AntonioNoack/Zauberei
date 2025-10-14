package me.anno.zauberei.astbuilder.expression

enum class PostfixType(val symbol: String) {
    INCREMENT("++"),
    DECREMENT("--"),
    ASSERT_NON_NULL("!!")
}

class PostfixExpression(val variable: Expression, val type: PostfixType, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(variable)
    }

    override fun toString(): String {
        return "$variable${type.symbol}"
    }
}