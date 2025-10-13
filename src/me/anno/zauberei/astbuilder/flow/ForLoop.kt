package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class ForLoop(
    val variableName: String, val variableType: Type?, val iterable: Expression,
    val body: Expression, val label: String?
) : Expression(iterable.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(iterable)
        callback(body)
    }
}