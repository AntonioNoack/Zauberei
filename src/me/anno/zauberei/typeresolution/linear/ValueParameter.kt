package me.anno.zauberei.typeresolution.linear

import me.anno.zauberei.types.Type

class ValueParameter(val name: String?, val type: Type) {
    override fun toString(): String {
        return if (name != null) "$name=$type" else "$type"
    }
}