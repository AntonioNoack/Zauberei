package me.anno.zauberei.types

class LambdaParameter(val name: String?, val type: Type) {
    override fun toString(): String {
        return "$name: $type"
    }
}