package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.types.Scope

@Suppress("FunctionName")
fun DeclarationExpression(
    scope: Scope, name: String, initialValue: Expression?,
    field: Field
): Expression {
    val origin = field.origin
    return if (initialValue != null) {
        val name1 = VariableExpression(name, scope, field, scope, origin)
        AssignmentExpression(name1, initialValue)
    } else {
        ExpressionList(emptyList(), scope, origin)
    }
}