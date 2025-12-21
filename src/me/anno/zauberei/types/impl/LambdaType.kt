package me.anno.zauberei.types.impl

import me.anno.zauberei.types.LambdaParameter
import me.anno.zauberei.types.Type

/**
 * Lambda type, with always known parameter types...
 * (A,B,C) -> R
 * */
class LambdaType(val parameters: List<LambdaParameter>, val returnType: Type) : Type() {
    override fun toString(): String {
        return "LambdaType((${parameters.joinToString(", ")}) -> $returnType)"
    }
}