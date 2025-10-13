package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

// todo we maybe can pack this into an return Err(thrown), and return into return Ok(value)
class ThrowExpression(origin: Int, val thrown: Expression) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(thrown)
    }

    override fun toString(): String {
        return "throw $thrown"
    }
}