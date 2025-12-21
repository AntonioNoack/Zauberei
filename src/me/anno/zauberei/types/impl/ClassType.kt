package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

/**
 * A scope, but also with optional type arguments,
 * e.g. ArrayList, ArrayList<Int> or Map<Key, Value>
 * */
class ClassType(val clazz: Scope, val typeParameters: List<Type>?) : Type() {

    override fun equals(other: Any?): Boolean {
        return other is ClassType &&
                clazz == other.clazz &&
                typeParameters == other.typeParameters
    }

    override fun hashCode(): Int {
        return clazz.pathStr.hashCode()
    }

    override fun toString(): String {
        return if (typeParameters != null && typeParameters.isEmpty()) {
            "ClassType(${clazz.pathStr})"
        } else {
            "ClassType<${typeParameters?.joinToString() ?: "?"}>(${clazz.pathStr})"
        }
    }

}