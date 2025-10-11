package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class TryCatchBlock(val tryBody: Expression, val catches: List<Catch>, val finallyExpression: Expression?) :
    Expression(tryBody.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(tryBody)
        for (catch in catches) {
            callback(catch.handler)
        }
        if (finallyExpression != null)
            callback(finallyExpression)
    }
}