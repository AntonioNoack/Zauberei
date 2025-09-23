package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ThrowExpression(val base: Expression) : Expression() {
    override fun toString(): String {
        return "throw $base"
    }
}