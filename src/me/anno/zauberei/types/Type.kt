package me.anno.zauberei.types

import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.LambdaType
import me.anno.zauberei.types.impl.NotType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType

abstract class Type {
    fun containsGenerics(): Boolean {
        return when (this) {
            NullType -> false
            is ClassType -> typeParameters?.any { it.containsGenerics() } ?: false
            is UnionType -> types.any { it.containsGenerics() }
            is GenericType -> true
            is LambdaType -> parameters.any { it.type.containsGenerics() } || returnType.containsGenerics()
            else -> throw NotImplementedError("Does $this contain generics?")
        }
    }

    open fun not(): Type = NotType(this)
}