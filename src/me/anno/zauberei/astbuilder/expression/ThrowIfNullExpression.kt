package me.anno.zauberei.astbuilder.expression

class ThrowIfNullExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "($base)!!"
    }
}