package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Type

enum class PrefixType(val symbol: String) {
    NOT("!"),
    MINUS("-"),
    INCREMENT("++"),
    DECREMENT("--"),
    ARRAY_TO_VARARGS("*")
}

class PrefixExpression(val type: PrefixType, origin: Int, val base: Expression) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "${type.symbol}$base"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return TypeResolution.resolveType(context, base)
    }
}