package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class SubjectWhenCase(val conditions: List<SubjectCondition?>, val body: Expression) {
    override fun toString(): String {
        return "${conditions.joinToString(", ")} -> { $body }"
    }
}