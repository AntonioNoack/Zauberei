package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.impl.ClassType

class SuperCall(
    val type: ClassType,
    val valueParams: List<NamedParameter>?,
    val delegate: Expression?
)