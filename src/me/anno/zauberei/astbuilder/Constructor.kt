package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.impl.ClassType

class Constructor(
    val selfType: ClassType,
    val valueParameters: List<Parameter>,
    val innerScope: Scope,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>,
    val origin: Int
) {

    val typeParameters: List<Parameter> get() = emptyList()

    override fun toString(): String {
        return "new ${selfType.clazz.pathStr}($valueParameters) { ... }"
    }
}