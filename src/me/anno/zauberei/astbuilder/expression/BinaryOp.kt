package me.anno.zauberei.astbuilder.expression

class BinaryOp(val left: Expression, val symbol: String, val right: Expression) : Expression() {
    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}