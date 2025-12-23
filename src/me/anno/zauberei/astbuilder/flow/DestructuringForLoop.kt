package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Scope

@Suppress("FunctionName")
fun DestructuringForLoop(
    scope: Scope,
    variableNames: List<String>, iterable: Expression,
    body: Expression, label: String?
): Expression {
    val origin = iterable.origin
    val fullName = scope.generateName("full")
    val field = Field(scope, false, true, scope.typeWithoutArgs, fullName, null, null, emptyList(), origin)
    val fields = variableNames.map { varName ->
        Field(scope, false, true, scope.typeWithoutArgs, varName, null, null, emptyList(), origin)
    }
    val fullExpr = FieldExpression(field, scope, origin)
    val newBody = ExpressionList(
        variableNames
            .withIndex()
            .filter { it.value != "_" }
            .map { (index, name) ->
                val newValue = NamedCallExpression(
                    fullExpr, "component${index + 1}", emptyList(),
                    emptyList(), fullExpr.scope, origin
                )
                val variableName = FieldExpression(fields[index], scope, origin)
                AssignmentExpression(variableName, newValue)
            } + body, scope, origin
    )
    return ForLoop(fullName, null, iterable, newBody, label)
}