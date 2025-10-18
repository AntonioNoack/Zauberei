package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope

@Suppress("FunctionName")
fun DestructuringForLoop(
    scope: Scope,
    variableNames: List<String>, iterable: Expression,
    body: Expression, label: String?
): Expression {
    val origin = iterable.origin
    val fullName = scope.generateName("full")
    Field(scope, false, true, scope, fullName, null, null, emptyList(), origin)
    for (varName in variableNames) {
        Field(scope, false, true, scope, varName, null, null, emptyList(), origin)
    }
    val fullExpr = VariableExpression(fullName, origin)
    val newBody = ExpressionList(
        variableNames
            .withIndex()
            .filter { it.value != "_" }
            .map { (index, name) ->
                val variable = VariableExpression(name, origin)
                val newValue = NamedCallExpression(fullExpr, "component${index + 1}", emptyList(), emptyList(), origin)
                AssignmentExpression(variable, newValue)
            } + body, origin
    )
    return ForLoop(fullName, null, iterable, newBody, label)
}