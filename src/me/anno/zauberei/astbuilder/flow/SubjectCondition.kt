package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class SubjectCondition(val value: Expression?, val keyword: String? /* is, !is, in, !in */) {
    override fun toString(): String {
        return "${if (keyword != null) "$keyword " else ""}$value"
    }
}