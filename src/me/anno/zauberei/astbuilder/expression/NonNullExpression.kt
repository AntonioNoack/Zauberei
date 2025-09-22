package me.anno.zauberei.astbuilder.expression

class NonNullExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "($base)!!"
    }
}