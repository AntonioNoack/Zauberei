package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Type

class AndType(val types: List<Type>) : Type() {

    companion object {
        /**
         * OR
         * */
        fun andTypes(typeA: Type, typeB: Type): Type {
            if (typeA == typeB) return typeA
            return AndType((getTypes(typeA) + getTypes(typeB)).distinct())
        }

        fun getTypes(type: Type): List<Type> {
            return if (type is AndType) type.types else listOf(type)
        }
    }

    init {
        check(types.size >= 2)
    }

    override fun toString(): String {
        return "AndType(${types.joinToString()})"
    }

    override fun equals(other: Any?): Boolean {
        return other is AndType &&
                types.toSet() == other.types.toSet()
    }

    override fun hashCode(): Int {
        return types.toSet().hashCode()
    }
}