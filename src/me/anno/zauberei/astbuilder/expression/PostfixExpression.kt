package me.anno.zauberei.astbuilder.expression

class PostfixExpression(val base: Expression, val symbol: String) : Expression() {
    override fun toString(): String {
        return "$base$symbol"
    }
}