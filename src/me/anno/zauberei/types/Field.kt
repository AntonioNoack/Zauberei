package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.expression.Expression

class Field(
    val isVar: Boolean,
    val isVal: Boolean,
    val name: String,
    val type: Type?,
    val initialValue: Expression?,
    val keywords: List<String>
)