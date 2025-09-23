package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression

class Constructor(
    val typeParameters: List<Parameter>,
    val parameters: List<Parameter>,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>
) : Expression()