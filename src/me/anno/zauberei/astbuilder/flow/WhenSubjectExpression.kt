package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhenSubjectExpression(val subject: Expression, val cases: List<SubjectWhenCase>) : Expression() {
    override fun toString(): String {
        return "when($subject) { ${cases.joinToString("; ")} }"
    }
}