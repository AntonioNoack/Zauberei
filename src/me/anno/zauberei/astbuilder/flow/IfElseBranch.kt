package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class IfElseBranch(val condition: Expression, val ifTrue: Expression, val ifFalse: Expression): Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(condition)
        callback(ifTrue)
        callback(ifFalse)
    }
}