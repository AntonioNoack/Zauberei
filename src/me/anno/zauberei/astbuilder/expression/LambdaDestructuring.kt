package me.anno.zauberei.astbuilder.expression

class LambdaDestructuring(val names: List<String>) : LambdaVariable(null, "") {
    override fun toString(): String {
        return "(${names.joinToString(", ")})"
    }
}