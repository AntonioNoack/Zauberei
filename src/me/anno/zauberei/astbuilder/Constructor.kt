package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope

class Constructor(
    val clazz: Scope,
    val valueParameters: List<Parameter>,
    val innerScope: Scope,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>,
    origin: Int
) {

    override fun toString(): String {
        return "new ${clazz.pathStr}($valueParameters) { ... }"
    }
}