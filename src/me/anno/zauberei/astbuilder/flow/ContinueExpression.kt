package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

class ContinueExpression(val label: String?, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return if (label != null) "continue@$label" else "continue"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return exprHasNoType(context)
    }

    override fun clone() = ContinueExpression(label, origin)
}