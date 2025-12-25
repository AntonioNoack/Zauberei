package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ShortcutOperator
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType

fun shortcutExpression(
    left: Expression, operator: ShortcutOperator, right: Expression,
    scope: Scope, origin: Int
): Expression {
    val bodyName = scope.generateName("shortcut")
    val bodyScope = scope.getOrPut(bodyName, ScopeType.EXPRESSION)
    val rightWithScope = right.clone(bodyScope)
    return when (operator) {
        ShortcutOperator.AND -> {
            val falseExpression = SpecialValueExpression(SpecialValue.TRUE, scope, origin)
            IfElseBranch(left, rightWithScope, falseExpression)
        }
        ShortcutOperator.OR -> {
            val trueExpression = SpecialValueExpression(SpecialValue.TRUE, scope, origin)
            IfElseBranch(left, trueExpression, rightWithScope)
        }
    }
}