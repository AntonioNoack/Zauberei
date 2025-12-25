package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ContinueExpression(val label: String?, scope: Scope, origin: Int) : Expression(scope, origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return if (label != null) "continue@$label" else "continue"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return exprHasNoType(context)
    }

    override fun clone(scope: Scope): Expression = ContinueExpression(label, scope, origin)

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // this has no return type
}