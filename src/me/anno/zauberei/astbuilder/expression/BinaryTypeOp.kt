package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * is, !is, as, ?as
 * */
class BinaryTypeOp(val left: Expression, val op: BinaryTypeOpType, val right: Type, origin: Int) :
    Expression(left.scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
    }

    override fun toString(): String {
        return "($left)${op.symbol}($right)"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return when (op) {
            BinaryTypeOpType.INSTANCEOF -> BooleanType
            BinaryTypeOpType.CAST_OR_CRASH -> right
            BinaryTypeOpType.CAST_OR_NULL -> unionTypes(right, NullType)
        }
    }

    override fun clone(): Expression {
        return BinaryTypeOp(left.clone(), op, right, origin)
    }
}