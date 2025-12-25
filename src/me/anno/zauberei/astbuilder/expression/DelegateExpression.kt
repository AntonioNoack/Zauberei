package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

// todo this generates a hidden field, initializes it, and creates a setter and getter method
class DelegateExpression(val delegate: Expression): Expression(delegate.scope, delegate.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(delegate)
    }

    override fun toString(): String {
        return "by $delegate"
    }

    override fun resolveType(context: ResolutionContext): Type {
        TODO("Not yet implemented")
    }

    override fun clone(scope: Scope) = DelegateExpression(delegate.clone(scope))

}