package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType

class ShortcutExpression(
    val left: Expression, val operator: ShortcutOperator, val right: Expression,
    scope: Scope, origin: Int
) : Expression(scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun resolveType(context: ResolutionContext): Type = BooleanType
    override fun hasLambdaOrUnknownGenericsType(): Boolean = false // is BooleanType

    override fun clone() = ShortcutExpression(left.clone(), operator, right.clone(), scope, origin)
}