package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type

class ValueParameterWithLambda(name: String?) : ValueParameter(name) {

    override fun getType(targetType: Type): Type {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        return "ValueParameterWithLambda(name=$name,)"
    }
}