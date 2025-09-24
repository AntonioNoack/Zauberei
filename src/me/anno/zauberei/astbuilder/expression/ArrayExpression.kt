package me.anno.zauberei.astbuilder.expression

class ArrayExpression(val base: Expression, val indices: List<Expression>) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in indices.indices) {
            callback(indices[i])
        }
    }

    override fun toString(): String {
        return "$base[${indices.joinToString(", ")}]"
    }
}