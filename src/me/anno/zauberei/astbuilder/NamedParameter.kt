package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression

/**
 * Used in calls: a named parameter
 * */
class NamedParameter(val name: String?, val value: Expression) {
    override fun toString(): String {
        return if (name != null) {
            "$name=$value"
        } else value.toString()
    }
}