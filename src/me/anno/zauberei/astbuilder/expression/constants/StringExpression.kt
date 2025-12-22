package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.StringType

class StringExpression(val value: String, scope: Scope, origin: Int) : Expression(scope, origin) {

    init {
        resolvedType = StringType
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = "\"$value\""

    override fun resolveType(context: ResolutionContext): Type {
        return StringType
    }

    override fun clone() = StringExpression(value, scope, origin)

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false

}