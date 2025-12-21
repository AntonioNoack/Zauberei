package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

class BreakExpression(val label: String?, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return if (label != null) "break@$label" else "break"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return asTypeless(context)
    }
}