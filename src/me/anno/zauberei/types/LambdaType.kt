package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.Parameter

class LambdaType(val parameters: List<Parameter>, val returnType:Type) : Type() {
    override fun toString(): String {
        return "(${parameters.joinToString(", ")}) -> $returnType"
    }
}