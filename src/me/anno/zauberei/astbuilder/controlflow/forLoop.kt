package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.expression.*

fun iterableToIterator(iterable: Expression): NamedCallExpression {
    return NamedCallExpression(
        iterable, "iterator",
        emptyList(), emptyList(), iterable.scope, iterable.origin
    )
}

fun iteratorToNext(iteratorField: Field, body: Expression): Expression {
    return NamedCallExpression(
        FieldExpression(iteratorField, body.scope, body.origin), "next",
        emptyList(), emptyList(), body.scope, body.origin
    )
}

/** just for deducting the type...
todo is there a better solution, that doesn't complicate our type system? */
fun iterableToNextExpr(iterable: Expression): Expression {
    val iterator = iterableToIterator(iterable)
    return NamedCallExpression(
        iterator, "next", emptyList(), emptyList(),
        iterator.scope, iterator.origin
    )
}

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
    val iterator = iterableToIterator(iterable)
    val iteratorField = Field(
        scope, false, true, null,
        iteratorFieldName, null, iterable,
        emptyList(), origin
    )
    val getNextCall = iteratorToNext(iteratorField, body)
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