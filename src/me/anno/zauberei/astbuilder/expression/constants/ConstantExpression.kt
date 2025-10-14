package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression

enum class Constant {
    TRUE,
    FALSE,
    NULL,
    THIS,
    SUPER,
    CLASS
}

class ConstantExpression(val value: Constant, origin: Int): Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = value.name.lowercase()
}