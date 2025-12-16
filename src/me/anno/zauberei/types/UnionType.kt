package me.anno.zauberei.types

class UnionType(val types: List<Type>) : Type() {
    override fun toString(): String {
        return "UnionType(${types.joinToString()})"
    }

    companion object {
        fun unionTypes(typeA: Type, typeB: Type): UnionType {
            return UnionType((getTypes(typeA) + getTypes(typeB)).distinct())
        }

        fun getTypes(type: Type): List<Type> {
            return if (type is UnionType) type.types else listOf(type)
        }
    }
}