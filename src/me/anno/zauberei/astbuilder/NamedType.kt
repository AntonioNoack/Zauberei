package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Type

class NamedType(val name: String?, val type: Type) {
    override fun toString(): String {
        return "GenericParam(${name ?: ""}=$type)"
    }
}