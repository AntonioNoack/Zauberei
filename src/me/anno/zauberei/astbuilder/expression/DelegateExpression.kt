package me.anno.zauberei.astbuilder.expression

// todo this generates a hidden field, initializes it, and creates a setter and getter method
class DelegateExpression(val delegate: Expression): Expression(delegate.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(delegate)
    }

    override fun toString(): String {
        return "by $delegate"
    }
}