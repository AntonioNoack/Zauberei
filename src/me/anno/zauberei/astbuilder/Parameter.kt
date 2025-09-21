package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class Parameter(
    val isVar: Boolean,
    val isVal: Boolean,
    val name: String,
    val type: Type,
    val initialValue: Expression?
)