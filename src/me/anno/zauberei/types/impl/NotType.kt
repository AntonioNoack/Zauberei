package me.anno.zauberei.types.impl

import me.anno.zauberei.types.Type

class NotType(val type: Type) : Type() {

    init {
        check(type !is NotType)
    }

    override fun not(): Type = type

    override fun toString(depth: Int): String {
        return "NotType(${type.toString(depth - 1)})"
    }

    override fun equals(other: Any?): Boolean {
        return other is NotType &&
                type == other.type
    }

    override fun hashCode(): Int {
        return type.hashCode()
    }
}