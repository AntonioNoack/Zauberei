package me.anno.zauberei.astbuilder.expression

/**
 * Generates a lambda from the base, effectively being a::b -> { a.b(allParamsNeeded) }
 * */
class GetMethodFromValueExpression(val base: Expression, val name: String, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "$base::$name"
    }
}