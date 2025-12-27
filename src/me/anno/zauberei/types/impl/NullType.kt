package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * Exactly null
 * */
object NullType : Type() {
    override fun toString(depth: Int): String = "NullType"

    fun typeOrNull(base: Type): Type {
        return unionTypes(base, NullType)
    }
}