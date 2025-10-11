package me.anno.zauberei.astbuilder.expression

class AssignIfMutableExpr(val left: Expression, val symbol: String, val right: Expression): Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun toString(): String {
        return "$left $symbol $right"
    }
}