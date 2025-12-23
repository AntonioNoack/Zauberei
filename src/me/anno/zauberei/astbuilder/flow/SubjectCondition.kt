package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.LambdaType

class SubjectCondition(
    val value: Expression?, val type: Type?,
    val subjectConditionType: SubjectConditionType,
    val extraCondition: Expression?
) {
    fun toExpression(astBuilder: ASTBuilder, subject: Expression, newScope: Scope): Expression {
        return when (subjectConditionType) {
            SubjectConditionType.EQUALS ->
                astBuilder.binaryOp(newScope, subject, "==", value!!)
            SubjectConditionType.INSTANCEOF ->
                buildIsExpr(this, subject, newScope)
            SubjectConditionType.NOT_INSTANCEOF ->
                PrefixExpression(PrefixType.NOT, subject.origin, buildIsExpr(this, subject, newScope))
            SubjectConditionType.CONTAINS, SubjectConditionType.NOT_CONTAINS ->
                astBuilder.binaryOp(newScope, subject, subjectConditionType.symbol, value!!)
        }
    }

    private fun buildIsExpr(expr: SubjectCondition, subject: Expression, newScope: Scope): Expression {
        val type = when (expr.type) {
            is ClassType -> expr.type
            is LambdaType -> lambdaTypeToClassType(expr.type)
            else -> throw NotImplementedError("Handle is ${expr.type?.javaClass}")
        }
        return ExprTypeOp(subject, ExprTypeOpType.INSTANCEOF, type, newScope, subject.origin)
    }

    override fun toString(): String {
        val prefix = subjectConditionType.symbol
        return "$prefix ${value ?: type}${if (extraCondition != null) " if ($extraCondition)" else ""}"
    }
}
