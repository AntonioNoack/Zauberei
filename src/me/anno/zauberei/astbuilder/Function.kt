package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class Function(
    val selfType: Type?,
    var name: String?,
    val typeParameters: List<Parameter>,
    val parameters: List<Parameter>,
    val returnType: Type?,
    val body: Expression?,
    val keywords: List<String>
) : Expression()