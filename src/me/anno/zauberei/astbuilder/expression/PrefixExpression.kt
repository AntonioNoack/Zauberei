package me.anno.zauberei.astbuilder.expression

class PrefixExpression(val symbol: String, val base: Expression): Expression(base.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$symbol$base"
    }
}