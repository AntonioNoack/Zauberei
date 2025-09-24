package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ReturnExpression(val value: Expression?, val label: String?) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        if (value != null) callback(value)
    }

    override fun toString(): String {
        return "return $value"
    }
}