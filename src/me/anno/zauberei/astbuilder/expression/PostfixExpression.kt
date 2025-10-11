package me.anno.zauberei.astbuilder.expression

class PostfixExpression(val variable: Expression, val type: PostfixMode, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(variable)
    }

    override fun toString(): String {
        return "$variable${type.symbol}"
    }
}