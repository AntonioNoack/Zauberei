package me.anno.zauberei.types.impl

import me.anno.zauberei.astbuilder.NamedType
import me.anno.zauberei.types.Type

class LambdaType(val parameters: List<NamedType>, val returnType: Type) : Type() {
    override fun toString(): String {
        return "LambdaType((${parameters.joinToString(", ")}) -> $returnType)"
    }
}