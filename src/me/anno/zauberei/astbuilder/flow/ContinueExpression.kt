package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ContinueExpression(val label: String?, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return if (label != null) "continue@$label" else "continue"
    }
}