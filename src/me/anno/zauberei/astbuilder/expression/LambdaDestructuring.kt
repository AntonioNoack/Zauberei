package me.anno.zauberei.astbuilder.expression

class LambdaDestructuring(val names: List<String>) : LambdaVariable("") {
    override fun toString(): String {
        return "(${names.joinToString(", ")})"
    }
}