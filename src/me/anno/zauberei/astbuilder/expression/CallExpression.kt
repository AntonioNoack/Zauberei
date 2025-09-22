package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

class CallExpression(val base: Expression, val typeParams: List<Type>, val params: List<Expression>) : Expression() {
    override fun toString(): String {
        return "($base)($params)"
    }
}