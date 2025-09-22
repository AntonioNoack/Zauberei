package me.anno.zauberei.astbuilder.expression

class ThrowExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "throw $base"
    }
}