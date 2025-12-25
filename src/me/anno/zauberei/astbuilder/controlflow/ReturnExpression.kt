package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.NothingType

class ReturnExpression(val value: Expression?, val label: String?, scope: Scope, origin: Int) :
    Expression(scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        if (value != null) callback(value)
    }

    override fun toString(): String {
        return "return $value"
    }

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // type is known: Nothing
    override fun resolveType(context: ResolutionContext): Type = NothingType
    override fun clone(scope: Scope) = ReturnExpression(value?.clone(scope), label, scope, origin)

}