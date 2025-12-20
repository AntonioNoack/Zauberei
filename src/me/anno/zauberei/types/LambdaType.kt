package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.NamedType

class LambdaType(val parameters: List<NamedType>, val returnType: Type) : Type() {
    override fun toString(): String {
        return "LambdaType((${parameters.joinToString(", ")}) -> $returnType)"
    }
}