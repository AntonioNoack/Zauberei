package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class PrefixExpression(val type: PrefixType, origin: Int, val base: Expression) : Expression(base.scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "${type.symbol}$base"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return TypeResolution.resolveType(context, base)
    }

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return base.hasLambdaOrUnknownGenericsType()
    }

    override fun clone(scope: Scope) = PrefixExpression(type, origin, base.clone(scope))
}