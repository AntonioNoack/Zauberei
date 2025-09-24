package me.anno.zauberei.astbuilder.expression

class ExpressionList(val members: List<Expression>) : Expression() {
    companion object {
        val empty = ExpressionList(emptyList())
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in members.indices) {
            callback(members[i])
        }
    }

    override fun toString(): String {
        return "[${members.joinToString("; ")}]"
    }
}