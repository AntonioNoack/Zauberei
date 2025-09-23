package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhenCase(val condition: Expression?, val body: Expression) {
    override fun toString(): String {
        return "${condition ?: "else"} -> { $body }"
    }
}