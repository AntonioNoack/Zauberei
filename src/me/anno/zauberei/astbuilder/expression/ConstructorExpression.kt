package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ConstructorExpression(
    val clazz: Scope,
    val typeParams: List<Type>,
    val params: List<Expression>,
    origin: Int
) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in params.indices) {
            callback(params[i])
        }
    }

    override fun toString(): String {
        return "new($clazz)($params)"
    }
}