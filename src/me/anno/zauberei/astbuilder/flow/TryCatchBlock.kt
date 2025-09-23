package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class TryCatchBlock(val tryBody: Expression, val catches: List<Catch>, val finallyExpression: Expression?): Expression() {
}