package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

/**
 * A scope, but also with type arguments
 * */
class ClassType(val clazz: Scope, val typeArgs: List<Type>?) : Type() {

    override fun equals(other: Any?): Boolean {
        return other is ClassType &&
                clazz == other.clazz &&
                typeArgs == other.typeArgs
    }

    override fun hashCode(): Int {
        return clazz.pathStr.hashCode()
    }

    override fun toString(): String {
        return if (typeArgs != null && typeArgs.isEmpty()) {
            "ClassType(${clazz.pathStr})"
        } else {
            "ClassType<${typeArgs?.joinToString() ?: "?"}>(${clazz.pathStr})"
        }
    }

}