package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

@Suppress("FunctionName")
fun DeclarationExpression(
    scope: Scope,
    name: String, type: Type?, initialValue: Expression?,
    isVar: Boolean, isLateinit: Boolean,
    origin: Int
): Expression {
    val field = Field(isVar, !isVar, scope, name, type, initialValue, emptyList())
    scope.fields.add(field)
    return if (initialValue != null) {
        AssignmentExpression(VariableExpression(name, scope, field, origin), initialValue)
    } else ExpressionList(emptyList(), origin)
}