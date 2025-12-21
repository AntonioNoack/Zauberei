package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.types.*
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.LambdaType

class SubjectCondition(
    val value: Expression?, val type: Type?, val keyword: String? /* is, !is, in, !in */,
    val extraCondition: Expression?
) {
    override fun toString(): String {
        return "${if (keyword != null) "$keyword " else ""}${value ?: type}"
    }
}

class SubjectWhenCase(val conditions: List<SubjectCondition?>, val body: Expression) {
    override fun toString(): String {
        return "${conditions.joinToString(", ")} -> { $body }"
    }

    fun toCondition(astBuilder: ASTBuilder, subject: Expression): Expression {
        val scope = astBuilder.currPackage
        return conditions.map { expr ->
            when (expr!!.keyword) {
                null -> astBuilder.binaryOp(scope, subject, "||", expr.value!!)
                "is" -> buildIsExpr(expr, subject)
                "!is" -> PrefixExpression(PrefixType.NOT, subject.origin, buildIsExpr(expr, subject))
                "in", "!in" -> astBuilder.binaryOp(scope, subject, expr.keyword, expr.value!!)
                else -> throw NotImplementedError()
            }
        }.reduce { a, b ->
            astBuilder.binaryOp(scope, a, "||", b)
        }
    }
}

fun buildIsExpr(expr: SubjectCondition, subject: Expression): NamedCallExpression {
    return when (expr.type) {
        is ClassType -> {
            val typeExpr = GetClassFromTypeExpression(expr.type.clazz, 0)
            val param = NamedParameter(null, subject)
            NamedCallExpression(typeExpr, "isInstance", emptyList(), listOf(param), subject.origin)
        }
        is LambdaType -> {
            val type = lambdaTypeToClassType(expr.type)
            val typeExpr = GetClassFromTypeExpression(type.clazz, 0)
            val param = NamedParameter(null, subject)
            NamedCallExpression(typeExpr, "isInstance", emptyList(), listOf(param), subject.origin)
        }
        else -> throw NotImplementedError("Handle is ${expr.type?.javaClass}")
    }
}

fun lambdaTypeToClassType(lambdaType: LambdaType): ClassType {
    val base = root.getOrPut("Function${lambdaType.parameters.size}", ScopeType.INTERFACE)
    return ClassType(base, lambdaType.parameters.map { it.type })
}

@Suppress("FunctionName")
fun ASTBuilder.WhenSubjectExpression(scope: Scope, subject: Expression, cases: List<SubjectWhenCase>): Expression {
    val origin = subject.origin
    val subjectName = scope.generateName("subject")
    Field(
        scope, false, true, scope, subjectName,
        null, subject, emptyList(), origin
    )
    val subjectV = VariableExpression(subjectName, origin, this)
    val assignment = AssignmentExpression(subjectV, subject)
    val cases = cases.map { case ->
        val condition =
            if (null !in case.conditions) case.toCondition(this, subjectV)
            else null
        WhenCase(condition, case.body)
    }
    return ExpressionList(listOf(assignment, WhenBranchExpression(cases, origin)), origin)
}