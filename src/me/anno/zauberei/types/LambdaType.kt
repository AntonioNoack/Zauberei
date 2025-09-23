package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.GenericParam

class LambdaType(val parameters: List<GenericParam>, val returnType: Type) : Type() {
    override fun toString(): String {
        return "(${parameters.joinToString(", ")}) -> $returnType"
    }
}