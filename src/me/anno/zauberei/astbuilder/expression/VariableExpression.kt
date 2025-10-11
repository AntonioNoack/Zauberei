package me.anno.zauberei.astbuilder.expression

class VariableExpression(val name: String, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = name
}