package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope

/**
 * ::callMeNow -> type is some lambda
 * */
class DoubleColonPrefix(val left: Scope, val methodName: String, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "($left)::$methodName"
    }
}