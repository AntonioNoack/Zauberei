package me.anno.zauberei.types.impl

import me.anno.zauberei.types.LambdaParameter
import me.anno.zauberei.types.Type

/**
 * Lambda type, with always known parameter types...
 * (A,B,C) -> R
 * */
class LambdaType(val parameters: List<LambdaParameter>, val returnType: Type) : Type() {
    override fun toString(depth: Int): String {
        val newDepth = depth - 1
        return "LambdaType((${
            parameters.joinToString(", ") {
                if (it.name != null) "${it.name}=${it.type.toString(newDepth)}"
                else it.type.toString(newDepth)
            }
        }) -> ${returnType.toString(newDepth)})"
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