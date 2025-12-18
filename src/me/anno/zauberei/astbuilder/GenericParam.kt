package me.anno.zauberei.astbuilder

import me.anno.zauberei.types.Type

class GenericParam(val name: String?, val type: Type) {
    override fun toString(): String {
        return "GenericParam(${name ?: ""}=$type)"
    }
}