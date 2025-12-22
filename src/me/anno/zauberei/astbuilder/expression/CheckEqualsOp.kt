package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType

class CheckEqualsOp(
    val left: Expression, val right: Expression,
    val byPointer: Boolean, val negated: Boolean,
    origin: Int
) : Expression(left.scope, origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    val symbol: String
        get() = when {
            byPointer && negated -> "!=="
            byPointer -> "==="
            negated -> "!="
            else -> "=="
        }

    override fun toString(): String {
        return "($left)$symbol($right)"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return BooleanType
    }

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // result is always Boolean

    override fun clone() = CheckEqualsOp(left.clone(), right.clone(), byPointer, negated, origin)
}