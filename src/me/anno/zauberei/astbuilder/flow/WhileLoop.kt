package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhileLoop(val condition: Expression, val body: Expression, val label: String?) :
    Expression(condition.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(condition)
        callback(body)
    }

    override fun toString(): String {
        return "${if (label != null) "$label@" else ""} while($condition) { $body }"
    }
}