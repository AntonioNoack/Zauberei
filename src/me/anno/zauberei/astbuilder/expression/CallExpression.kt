package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

class CallExpression(val base: Expression, val typeParams: List<Type>, val params: List<Expression>) : Expression() {

    init {
        if (toString() == "(assert)([\"companion\"])")
            throw IllegalArgumentException("Invalid call constructed")
    }

    override fun toString(): String {
        return if (typeParams.isEmpty()) {
            "($base)($params)"
        } else {
            "($base)<${typeParams.joinToString()}>($params)"
        }
    }
}