package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Type

/**
 * Each field might have a more specific type in each specific sub-scope
 *
 * todo this is influenced by if-conditions
 * todo this is influenced by every single assignment
 * todo after branches, those control flows that still flow need to have their types merged
 *
 * todo we should execute this logic before and after generics-specialization
 * */
class ScopedFieldType(
    val origin0: Int,
    val origin1: Int,
    val type: Type
)