package me.anno.zauberei.astbuilder.expression

class StringExpression(val value: String): Expression() {
    override fun toString(): String = "\"$value\""
}