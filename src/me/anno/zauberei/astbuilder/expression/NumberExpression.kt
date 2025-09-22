package me.anno.zauberei.astbuilder.expression

class NumberExpression(val value: String): Expression() {
    override fun toString(): String {
        return "#$value"
    }
}