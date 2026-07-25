package me.anno.zauber.ast.rich.expression

import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type

class NotNullExpression(val expr: Expression) : Expression(expr.scope, expr.origin) {

    override fun clone(scope: Scope): Expression = NotNullExpression(expr.clone(scope))
    override fun toStringImpl(depth: Int): String = "($expr)!!"

    override fun needsBackingField(methodScope: Scope): Boolean = expr.needsBackingField(methodScope)
    override fun splitsScope(): Boolean = expr.splitsScope()
    override fun isResolved(): Boolean = expr.isResolved()

    override fun resolveValueType(context: ResolutionContext): Type =
        expr.resolveValueType(context).notNull()

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean =
        expr.hasLambdaOrUnknownGenericsType(context)

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(expr)
    }

    override fun resolveImpl(context: ResolutionContext): Expression {
        return NotNullExpression(expr.resolve(context))
    }

}