package me.anno.zauberei.astbuilder.expression

class ExpressionList(val members: List<Expression>, origin: Int) : Expression(origin) {
    companion object {
        val empty = ExpressionList(emptyList(),-1)
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