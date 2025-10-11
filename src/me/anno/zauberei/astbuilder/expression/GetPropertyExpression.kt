package me.anno.zauberei.astbuilder.expression

class GetPropertyExpression(val base: Expression, val name: String) : Expression(base.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "($base).$name"
    }
}