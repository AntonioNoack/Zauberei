package me.anno.zauberei.astbuilder.expression

class NamedExpression(val name: String, val base: Expression) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$name=$base"
    }
}