package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Type

/**
 * Each field might have a more specific type in each specific sub-scope
 * */
class ScopedFieldType(
    val origin0: Int,
    val origin1: Int,
    val type: Type
)