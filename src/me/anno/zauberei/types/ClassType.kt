package me.anno.zauberei.types

class ClassType(val clazz: Scope, val typeArgs: List<Type>?, val subType: Type? = null) : Type() {

    override fun equals(other: Any?): Boolean {
        return other is ClassType &&
                clazz == other.clazz &&
                typeArgs == other.typeArgs &&
                subType == other.subType
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