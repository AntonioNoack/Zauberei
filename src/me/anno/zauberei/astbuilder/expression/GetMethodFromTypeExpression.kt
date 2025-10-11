package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope

class GetMethodFromTypeExpression(val base: Scope, val name: String, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
    }

    override fun toString(): String {
        return "$base::$name"
    }
}