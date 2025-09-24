package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhenSubjectExpression(val subject: Expression, val cases: List<SubjectWhenCase>) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(subject)
        for (case in cases) {
            for (condition in case.conditions) {
                condition ?: continue
                if (condition.value != null) {
                    callback(condition.value)
                }
            }
            callback(case.body)
        }
    }

    override fun toString(): String {
        return "when($subject) { ${cases.joinToString("; ")} }"
    }
}