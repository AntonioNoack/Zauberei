package me.anno.zauberei.astbuilder.expression

class UnaryOp(val symbol: String, val base: Expression): Expression() {
    override fun toString(): String {
        return "$symbol$base"
    }
}