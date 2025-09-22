package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class ForLoop(
    val variableName: String, val iterable: Expression,
    val body: Expression, val label: String?
) : Expression()