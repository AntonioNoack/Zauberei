package me.anno.zauberei.astbuilder.expression

class PrefixExpression(val type: PrefixType, origin: Int, val base: Expression) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "${type.symbol}$base"
    }
}