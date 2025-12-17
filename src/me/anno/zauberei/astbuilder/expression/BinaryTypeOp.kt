package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

/**
 * is, !is, as, ?as
 * */
class BinaryTypeOp(val left: Expression, val op: BinaryTypeOpType, val right: Type, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
    }

    override fun toString(): String {
        return "($left)${op.symbol}($right)"
    }
}