package me.anno.zauberei.astbuilder.expression

class VariableExpression(val name: String) : Expression() {
    override fun toString(): String = name
}