package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * Exactly null
 * */
object NullType : Type() {
    override fun toString(): String = "NullType"

    fun typeOrNull(base: Type): Type {
        return unionTypes(base, NullType)
    }
}