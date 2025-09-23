package me.anno.zauberei.types

class UnresolvedType(val clazz: String, val typeArgs: List<Type>) : Type() {
    override fun toString(): String {
        return clazz
    }
}