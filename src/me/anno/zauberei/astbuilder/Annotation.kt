package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Type

class Annotation(val path: Type, val params: List<NamedParameter>) {
    override fun toString(): String {
        return "@$path(${params.joinToString(", ")})"
    }
}