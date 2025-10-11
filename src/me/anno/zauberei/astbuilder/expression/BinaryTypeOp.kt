package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

/**
 * is, !is, as, ?as
 * */
class BinaryTypeOp(val left: Expression, val symbol: String, val right: Type) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
    }

    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}