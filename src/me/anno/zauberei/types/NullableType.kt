package me.anno.zauberei.types

class NullableType(val base: Type) : Type() {
    override fun toString(): String {
        return "$base?"
    }
}