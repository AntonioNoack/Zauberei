package me.anno.zauberei.astbuilder.expression

class ThrowIfNullExpression(val base: Expression) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "($base)!!"
    }
}