package me.anno.zauberei.types

fun NullableType(base: Type): Type {
    return when (base) {
        is UnionType if base.types.contains(NullType) -> {
            base
        }
        is UnionType -> {
            UnionType(base.types + NullType)
        }
        else -> {
            UnionType(listOf(base, NullType))
        }
    }
}