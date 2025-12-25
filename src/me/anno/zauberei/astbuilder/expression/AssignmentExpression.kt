package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class AssignmentExpression(var variableName: Expression, var newValue: Expression) :
    Expression(newValue.scope, newValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(variableName)
        callback(newValue)
    }

    override fun toString(): String {
        return "$variableName=$newValue"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return exprHasNoType(context)
    }

    override fun clone(scope: Scope): Expression = AssignmentExpression(variableName.clone(scope), newValue.clone(scope))

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // this has no return type
}