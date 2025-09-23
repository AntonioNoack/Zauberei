package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class SubjectCondition(val value: Expression?, val type: Type?, val keyword: String? /* is, !is, in, !in */) {
    override fun toString(): String {
        return "${if (keyword != null) "$keyword " else ""}${value ?: type}"
    }
}