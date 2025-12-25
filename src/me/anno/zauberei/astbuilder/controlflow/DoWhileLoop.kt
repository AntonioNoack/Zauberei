package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList
import me.anno.zauberei.astbuilder.expression.PrefixExpression
import me.anno.zauberei.astbuilder.expression.PrefixType
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression

@Suppress("FunctionName")
fun DoWhileLoop(body: Expression, condition: Expression, label: String?): Expression {
    val origin = body.origin
    val negatedCondition = PrefixExpression(PrefixType.NOT, origin, condition)
    val breakI = BreakExpression(label, body.scope, origin)
    val newBody = ExpressionList(
        listOf(
            condition,
            IfElseBranch(negatedCondition, breakI, null)
        ), body.scope, origin
    )
    return WhileLoop(SpecialValueExpression(SpecialValue.TRUE, body.scope, origin), newBody, label)
}