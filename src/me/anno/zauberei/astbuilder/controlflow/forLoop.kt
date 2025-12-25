package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.expression.*

// for-loop with else like Python? :) idk if we want that complexity...
//  we would need break-with-expression
/**
 * val tmp = iterable()
 * while(tmp.hasNext()) {
 *    val variableName: variableType = tmp.next()
 *    body()
 * }
 * */
fun forLoop(
    variableField: Field, iterable: Expression,
    body: Expression, label: String?
): Expression {
    val scope = iterable.scope
    val origin = iterable.origin
    val iteratorFieldName = scope.generateName("for")
    val iterator = NamedCallExpression(
        iterable, "iterator",
        emptyList(), emptyList(), scope, origin
    )
    val iteratorField = Field(
        scope, false, true, null,
        iteratorFieldName, null, iterable,
        emptyList(), origin
    )
    val getNextCall = NamedCallExpression(
        FieldExpression(iteratorField, body.scope, body.origin), "next",
        emptyList(), emptyList(), body.scope, body.origin
    )
    val outerAssignment = DeclarationExpression(scope, iterator, iteratorField)
    val innerAssignment = DeclarationExpression(body.scope, getNextCall, variableField)
    val hasNextCall = NamedCallExpression(
        FieldExpression(iteratorField, scope, origin), "hasNext",
        emptyList(), emptyList(), scope, origin
    )
    val newBody = ExpressionList(listOf(innerAssignment, body), body.scope, body.origin)
    val loop = WhileLoop(hasNextCall, newBody, label)
    return ExpressionList(listOf(outerAssignment, loop), scope, origin)
}