package me.anno.zauberei.astbuilder.expression

class DecExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "$base++"
    }
}