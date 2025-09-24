package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope

class Annotation(val path: Scope, val params: List<Expression>) {
    override fun toString(): String {
        return "@$path(${params.joinToString(", ")})"
    }
}