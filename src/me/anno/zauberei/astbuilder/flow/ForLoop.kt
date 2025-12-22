package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

// todo for-loop with else like Python? :) idk if we want that complexity...
class ForLoop(
    val variableName: String, val variableType: Type?, val iterable: Expression,
    val body: Expression, val label: String?
) : Expression(iterable.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(iterable)
        callback(body)
    }

    override fun resolveType(context: ResolutionContext): Type {
        return exprHasNoType(context)
    }

    override fun clone() = ForLoop(variableName, variableType, iterable.clone(), body.clone(), label)

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // this has no return type
}