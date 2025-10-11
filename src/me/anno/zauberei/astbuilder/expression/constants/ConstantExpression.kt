package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression

class ConstantExpression(val value: Constant, origin: Int): Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = value.name.lowercase()

    enum class Constant {
        TRUE,
        FALSE,
        NULL,
        THIS,
        SUPER,
        CLASS
    }
}