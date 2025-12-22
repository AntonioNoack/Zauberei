package me.anno.zauberei.astbuilder.flow

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

    override fun resolveType(context: ResolutionContext): Type {
        return NothingType
    }

    override fun clone() = ReturnExpression(value?.clone(), label, scope, origin)

}