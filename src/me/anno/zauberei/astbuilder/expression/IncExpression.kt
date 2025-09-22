package me.anno.zauberei.astbuilder.expression

class IncExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "$base++"
    }
}