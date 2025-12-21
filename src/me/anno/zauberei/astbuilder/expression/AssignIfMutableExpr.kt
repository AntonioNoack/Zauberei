package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

/**
 * this.name [+=, *=, /=, ...] right
 * */
class AssignIfMutableExpr(val left: Expression, val symbol: String, val right: Expression) :
    Expression(right.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun toString(): String {
        return "$left $symbol $right"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return UnitType
    }

    override fun clone() = AssignIfMutableExpr(left.clone(), symbol, right.clone())
}