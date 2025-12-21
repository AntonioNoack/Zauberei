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

    override fun equals(other: Any?): Boolean {
        return other is LambdaType &&
                parameters == other.parameters &&
                returnType == other.returnType
    }

    override fun hashCode(): Int {
        return parameters.hashCode() * 31 + returnType.hashCode()
    }
}