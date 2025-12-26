package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Types.BooleanType

object BooleanUtils {
    fun Expression.not(): Expression {
        if (this is ExprTypeOp) {
            when (op) {
                ExprTypeOpType.INSTANCEOF -> return ExprTypeOp(
                    left, ExprTypeOpType.NOT_INSTANCEOF, right,
                    scope, origin
                )
                ExprTypeOpType.NOT_INSTANCEOF -> return ExprTypeOp(
                    left, ExprTypeOpType.INSTANCEOF, right,
                    scope, origin
                )
                else -> {}
            }
        }

        if (this is CheckEqualsOp) {
            return CheckEqualsOp(left, right, byPointer, !negated, scope, origin)
        }

        resolvedType = BooleanType
        return PrefixExpression(PrefixType.NOT, origin, this).apply {
            resolvedType = BooleanType
        }
    }

    fun Expression.and(other: Expression): Expression {
        resolvedType = BooleanType
        other.resolvedType = BooleanType
        return NamedCallExpression(
            this, "and", emptyList(),
            listOf(NamedParameter(null, other)),
            scope, origin
        ).apply {
            resolvedType = BooleanType
        }
    }
}