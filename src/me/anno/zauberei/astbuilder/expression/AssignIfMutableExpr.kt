package me.anno.zauberei.astbuilder.expression

/**
 * this.name [+=, *=, /=, ...] right
 * */
class AssignIfMutableExpr(val left: Expression, val symbol: String, val right: Expression): Expression(right.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun toString(): String {
        return "$left $symbol $right"
    }
}