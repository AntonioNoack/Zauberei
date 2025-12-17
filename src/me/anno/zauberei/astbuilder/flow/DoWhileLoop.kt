package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList
import me.anno.zauberei.astbuilder.expression.PrefixExpression
import me.anno.zauberei.astbuilder.expression.PrefixType
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression

@Suppress("FunctionName")
fun DoWhileLoop(body: Expression, condition: Expression, label: String?): Expression {
    val origin = body.origin
    val newBody = ExpressionList(
        listOf(
            condition,
            IfElseBranch(PrefixExpression(PrefixType.NOT, origin, condition), BreakExpression(label, origin), null)
        ), origin
    )
    return WhileLoop(SpecialValueExpression(SpecialValue.TRUE, origin), newBody, label)
}