package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ConstructorExpression2(
    val clazz: Scope,
    val typeParams: List<Type>,
    val params: List<Expression>
) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in params.indices) {
            callback(params[i])
        }
    }

    override fun toString(): String {
        return "new($clazz)($params)"
    }
}