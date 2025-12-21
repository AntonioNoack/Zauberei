package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class SubjectCondition(
    val value: Expression?, val type: Type?,
    val subjectConditionType: SubjectConditionType,
    val extraCondition: Expression?
) {
    override fun toString(): String {
        val prefix = subjectConditionType.symbol
        return "$prefix ${value ?: type}${if(extraCondition != null) " if ($extraCondition)" else ""}"
    }
}
