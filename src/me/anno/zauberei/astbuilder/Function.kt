package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Package
import me.anno.zauberei.types.Type

class Function(
    var name: String?,
    val typeParameters: List<Package>,
    val parameters: List<Parameter>,
    val returnType: Type?,
    val body: Expression?,
    val keywords: List<String>
)