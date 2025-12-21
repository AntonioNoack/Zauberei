package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Type

class UnionType(val types: List<Type>) : Type() {

    companion object {
        /**
         * OR
         * */
        fun unionTypes(typeA: Type, typeB: Type): Type {
            if (typeA == typeB) return typeA
            return UnionType((getTypes(typeA) + getTypes(typeB)).distinct())
        }

        fun getTypes(type: Type): List<Type> {
            return if (type is UnionType) type.types else listOf(type)
        }
    }

    override fun toString(): String {
        return "UnionType(${types.joinToString()})"
    }

    override fun equals(other: Any?): Boolean {
        return other is UnionType &&
                types.toSet() == other.types.toSet()
    }

    override fun hashCode(): Int {
        return types.toSet().hashCode()
    }
}