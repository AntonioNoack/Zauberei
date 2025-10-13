package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope

class GetClassFromTypeExpression(val base: Scope, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {}

    override fun toString(): String {
        return "$base::class"
    }
}