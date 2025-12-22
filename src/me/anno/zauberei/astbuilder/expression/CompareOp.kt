package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType

class CompareOp(val value: Expression, val type: CompareType) : Expression(value.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(value)
    }

    override fun toString(): String {
        return "($value) ${type.symbol} 0"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return BooleanType
    }

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // return type is always Boolean

    override fun clone() = CompareOp(value.clone(), type)
}