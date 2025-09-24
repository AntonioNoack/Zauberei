package me.anno.zauberei.types

class ClassType(val clazz: Scope, val typeArgs: List<Type>, val subType: Type? = null) : Type() {
    override fun toString(): String {
        return clazz.path.joinToString(".")
    }
}