package me.anno.zauberei.types

class NamedType(val type: ClassType) : Type() {
    override fun toString(): String {
        return "NamedType($type)"
    }
}