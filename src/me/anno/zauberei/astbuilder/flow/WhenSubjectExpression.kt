package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.LambdaType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

class SubjectWhenCase(val conditions: List<SubjectCondition?>, val conditionScope: Scope, val body: Expression) {

    override fun toString(): String {
        return "${conditions.joinToString(", ")} -> { $body }"
    }

    fun toCondition(
        astBuilder: ASTBuilder,
        subject: Expression,
    ): Expression {
        val scope = conditionScope
        return conditions.map { expr ->
            when (expr!!.subjectConditionType) {
                SubjectConditionType.EQUALS ->
                    astBuilder.binaryOp(scope, subject, "||", expr.value!!)
                SubjectConditionType.INSTANCEOF ->
                    buildIsExpr(expr, subject, scope)
                SubjectConditionType.NOT_INSTANCEOF ->
                    PrefixExpression(PrefixType.NOT, subject.origin, buildIsExpr(expr, subject, scope))
                SubjectConditionType.CONTAINS, SubjectConditionType.NOT_CONTAINS ->
                    astBuilder.binaryOp(scope, subject, expr.subjectConditionType.symbol, expr.value!!)
            }
        }.reduce { a, b ->
            astBuilder.binaryOp(scope, a, "||", b)
        }
    }
}

fun buildIsExpr(
    expr: SubjectCondition, subject: Expression,
    newScope: Scope
): NamedCallExpression {
    return when (expr.type) {
        is ClassType -> {
            val typeExpr = GetClassFromTypeExpression(expr.type.clazz, subject.scope, subject.origin)
            val param = NamedParameter(null, subject)
            NamedCallExpression(
                typeExpr, "isInstance",
                emptyList(), listOf(param), newScope, subject.origin
            )
        }
        is LambdaType -> {
            val type = lambdaTypeToClassType(expr.type)
            val typeExpr = GetClassFromTypeExpression(type.clazz, subject.scope, subject.origin)
            val param = NamedParameter(null, subject)
            NamedCallExpression(
                typeExpr, "isInstance",
                emptyList(), listOf(param), newScope, subject.origin
            )
        }
        else -> throw NotImplementedError("Handle is ${expr.type?.javaClass}")
    }
}

fun lambdaTypeToClassType(lambdaType: LambdaType): ClassType {
    val base = root.getOrPut("Function${lambdaType.parameters.size}", ScopeType.INTERFACE)
    return ClassType(base, lambdaType.parameters.map { it.type })
}

fun ASTBuilder.whenSubjectToIfElseChain(scope: Scope, subject: Expression, cases: List<SubjectWhenCase>): Expression {
    val origin = subject.origin
    val subjectName = scope.generateName("subject")
    val value = if (subject is AssignmentExpression) subject.newValue else subject
    Field(
        scope, false, true, scope.typeWithoutArgs, subjectName,
        null, value, emptyList(), origin
    )

    val subjectExpr = VariableExpression(subjectName, origin, this, scope)
    val assignment = AssignmentExpression(subjectExpr, subject)
    val cases = cases.map { case ->
        val condition =
            if (null !in case.conditions) {
                case.toCondition(this, subjectExpr)
            } else null // else-case
        // if all conditions are 'is X',
        //  then join them together, and insert a field with more specific type...
        if (case.conditions.all { it != null && it.subjectConditionType == SubjectConditionType.INSTANCEOF }) {
            val fieldName = when (subject) {
                is AssignmentExpression -> (subject.variableName as VariableExpression).name
                is VariableExpression -> subject.name
                else -> null
            }
            if (fieldName != null) {
                val caseScope = case.body.scope
                val jointType = case.conditions.map { it!!.type!! }.reduce { a, b -> unionTypes(a, b) }
                // todo this more-specific field is only valid until fieldName is assigned, again, then we have to use unionType
                // todo this is also only valid, if no other thread/function could write to the field
                Field(
                    caseScope, false, false, null, fieldName,
                    jointType, null, emptyList(), origin
                )
            }
        }
        if (condition != null) {
            check(condition.scope == case.conditionScope)
        }
        if (false) {
            println("new-case:")
            println("  condition: ${condition?.scope?.pathStr}")
            println("  body: ${case.body.scope.pathStr}")
        }
        WhenCase(condition, case.body)
    }

    val whenExpression = whenBranchToIfElseChain(cases, subject.scope, origin)
    return ExpressionList(listOf(assignment, whenExpression), subject.scope, origin)
}