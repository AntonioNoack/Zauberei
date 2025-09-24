package me.anno.zauberei.astbuilder.expression

class GetPropertyExpression(val base: Expression, val name: String) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }
}