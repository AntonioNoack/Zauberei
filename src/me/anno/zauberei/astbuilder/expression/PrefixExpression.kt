package me.anno.zauberei.astbuilder.expression

class PrefixExpression(val symbol: String, val base: Expression): Expression() {
    override fun toString(): String {
        return "$symbol$base"
    }
}