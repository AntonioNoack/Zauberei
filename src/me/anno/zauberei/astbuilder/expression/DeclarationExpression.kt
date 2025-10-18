package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope

@Suppress("FunctionName")
fun DeclarationExpression(
    scope: Scope, name: String, initialValue: Expression?,
    field: Field
): Expression {
    val origin = field.origin
    return if (initialValue != null) {
        AssignmentExpression(VariableExpression(name, scope, field, origin), initialValue)
    } else {
        ExpressionList(emptyList(), origin)
    }
}