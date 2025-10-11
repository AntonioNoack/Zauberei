package me.anno.zauberei.astbuilder.expression

class AssignmentExpression(var nameOrArraySet: Expression, var newValue: Expression) : Expression(newValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(nameOrArraySet)
        callback(newValue)
    }

    override fun toString(): String {
        return "$nameOrArraySet=$newValue"
    }
}