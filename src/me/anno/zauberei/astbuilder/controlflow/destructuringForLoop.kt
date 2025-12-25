package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Scope

fun destructuringForLoop(
    scope: Scope,
    variableNames: List<String>, iterable: Expression,
    body: Expression, label: String?
): Expression {
    val origin = iterable.origin
    val fullName = scope.generateName("destruct")
    val fullVariable = Field(
        scope, false, true,
        scope.typeWithoutArgs, fullName,
        null, null,
        emptyList(), origin
    )
    val fields = variableNames.map { varName ->
        Field(
            scope, false, true,
            scope.typeWithoutArgs, varName,
            null, null,
            emptyList(), origin
        )
    }
    val fullExpr = FieldExpression(fullVariable, scope, origin)
    val newBody = ExpressionList(
        variableNames
            .withIndex()
            .filter { it.value != "_" }
            .map { (index, _) ->
                val newValue = NamedCallExpression(
                    fullExpr, "component${index + 1}", emptyList(),
                    emptyList(), scope, origin
                )
                val variableName = FieldExpression(fields[index], scope, origin)
                AssignmentExpression(variableName, newValue)
            } + body, scope, origin
    )
    return forLoop(fullVariable, iterable, newBody, label)
}