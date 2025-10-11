package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Scope

/**
 * ::callMeNow
 * */
class DoubleColonPrefix(val left: Scope, val symbol: String, val right: Expression) : Expression(right.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(right)
    }

    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}