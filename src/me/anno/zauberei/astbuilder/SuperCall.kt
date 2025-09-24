package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class SuperCall(
    val type: Type,
    val params: List<Expression>?,
    val delegate: Expression?
)