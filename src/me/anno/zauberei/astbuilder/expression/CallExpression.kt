package me.anno.zauberei.astbuilder.expression

class CallExpression(val base: Expression, val params: List<Expression>) : Expression() {
    override fun toString(): String {
        return "($base)($params)"
    }
}