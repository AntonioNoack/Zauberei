package me.anno.zauberei.astbuilder.expression

class ExpressionList(val members: List<Expression>): Expression() {
    companion object {
        val empty = ExpressionList(emptyList())
    }

    override fun toString(): String {
        return "[${members.joinToString("; ")}]"
    }
}