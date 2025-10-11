package me.anno.zauberei.astbuilder.expression

class ExpressionList(val list: List<Expression>, origin: Int) : Expression(origin) {
    companion object {
        val empty = ExpressionList(emptyList(),-1)
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in list.indices) {
            callback(list[i])
        }
    }

    override fun toString(): String {
        return "[${list.joinToString("; ")}]"
    }
}