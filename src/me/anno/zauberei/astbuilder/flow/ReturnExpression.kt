package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ReturnExpression(val base: Expression?, val label: String?) : Expression() {
    override fun toString(): String {
        return "return $base"
    }
}