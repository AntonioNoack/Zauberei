package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ExpressionList(val list: List<Expression>, scope: Scope, origin: Int) : Expression(scope, origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in list.indices) {
            callback(list[i])
        }
    }

    override fun toString(): String {
        return "[${list.joinToString("; ")}]"
    }

    override fun resolveType(context: ResolutionContext): Type {
        val lastExpr = list.lastOrNull() ?: return exprHasNoType(context)
        return TypeResolution.resolveType(context, lastExpr)
    }

    override fun clone(scope: Scope) = ExpressionList(list.map { it.clone(scope) }, scope, origin)

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        val last = list.lastOrNull() ?: return false
        return last.hasLambdaOrUnknownGenericsType()
    }

}