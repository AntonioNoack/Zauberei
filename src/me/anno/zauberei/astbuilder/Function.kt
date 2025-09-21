package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class Function(
    var name: String?,
    val parameters: List<Type>,
    val returnType: Type,
    val body: Expression
)