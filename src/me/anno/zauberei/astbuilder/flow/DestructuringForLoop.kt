package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class DestructuringForLoop(
    val variableNames: List<String>, val iterable: Expression,
    val body: Expression, val label: String?
) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(iterable)
        callback(body)
    }
}