package me.anno.zauberei.astbuilder.expression

class PostfixExpression(val base: Expression, val symbol: String, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$base$symbol"
    }
}