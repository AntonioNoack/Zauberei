package me.anno.zauberei.astbuilder.expression

class ArrayExpression(val base: Expression, val indices: List<Expression>) : Expression() {
    override fun toString(): String {
        return "$base[${indices.joinToString(", ")}]"
    }
}