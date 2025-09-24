package me.anno.zauberei.astbuilder.expression

class BinaryOp(val left: Expression, val symbol: String, val right: Expression) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}