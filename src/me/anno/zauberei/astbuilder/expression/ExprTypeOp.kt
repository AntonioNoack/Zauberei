package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * is, !is, as, ?as
 * */
class ExprTypeOp(val left: Expression, val op: ExprTypeOpType, val right: Type, scope: Scope, origin: Int) :
    Expression(scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
    }

    override fun toString(): String {
        return "($left)${op.symbol}($right)"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return when (op) {
            ExprTypeOpType.INSTANCEOF, ExprTypeOpType.NOT_INSTANCEOF -> BooleanType
            ExprTypeOpType.CAST_OR_CRASH -> right
            ExprTypeOpType.CAST_OR_NULL -> unionTypes(right, NullType)
        }
    }

    override fun clone(): Expression {
        return ExprTypeOp(left.clone(), op, right, scope, origin)
    }
}