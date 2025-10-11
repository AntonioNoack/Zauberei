package me.anno.zauberei.astbuilder.expression

class AssignmentExpression(var variableName: Expression, var newValue: Expression) : Expression(newValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(variableName)
        callback(newValue)
    }

    override fun toString(): String {
        return "$variableName=$newValue"
    }
}