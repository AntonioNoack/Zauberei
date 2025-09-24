package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression

class StringExpression(val value: String): Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = "\"$value\""
}