package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.types.Scope

@Suppress("FunctionName")
fun DeclarationExpression(
    scope: Scope, initialValue: Expression?,
    field: Field
): Expression {
    val origin = field.origin
    return if (initialValue != null) {
        val variableName = FieldExpression(field, scope, origin)
        AssignmentExpression(variableName, initialValue)
    } else {
        ExpressionList(emptyList(), scope, origin)
    }
}