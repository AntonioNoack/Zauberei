package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class EnumEntry(
    val name: String,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    val scope: Scope,
    val origin: Int
)