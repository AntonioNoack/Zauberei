package me.anno.zauberei.astbuilder.expression

class DelegateExpression(val delegate: Expression): Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(delegate)
    }

    override fun toString(): String {
        return "by $delegate"
    }
}