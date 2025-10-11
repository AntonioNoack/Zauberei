package me.anno.zauberei.astbuilder.expression

class GetClassFromValueExpression(val type: Expression, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(type)
    }

    override fun toString(): String {
        return "($type)::class"
    }
}