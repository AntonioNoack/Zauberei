package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ThrowExpression(val thrown: Expression) : Expression(thrown.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(thrown)
    }

    override fun toString(): String {
        return "throw $thrown"
    }
}