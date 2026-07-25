package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.ast.rich.controlflow.IfElseBranch
import me.anno.zauber.ast.rich.controlflow.ThrowExpression
import me.anno.zauber.ast.rich.expression.CheckEqualsOp
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedSetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.ValueParameterImpl
import me.anno.zauber.typeresolution.members.ConstructorResolver
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

/**
 * left!!
 * */
class EnsureNotNullExpression(
    val value: Expression,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    override fun clone(scope: Scope) = EnsureNotNullExpression(value.clone(scope), scope, origin)

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        val contextI = context.withTargetType(context.targetType?.notNull())
        return value.hasLambdaOrUnknownGenericsType(contextI)
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return value.needsBackingField(methodScope)
    }

    override fun splitsScope(): Boolean {
        return value.splitsScope()
    }

    override fun isResolved(): Boolean = false

    override fun toStringImpl(depth: Int): String {
        return "${value.toString(depth)}!!"
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        val contextI = context.withTargetType(context.targetType?.notNull())
        return value.resolveValueType(contextI).notNull()
    }

    override fun resolveImpl(context: ResolutionContext): Expression {
        val base = value.resolve(context)
        val contextI = context.withSelfType(null).withTargetType(null)
        val field = scope.createImmutableField(base, "notNull", origin)
        val ownerR = ThisExpression(field.ownerScope, scope, origin)
        val fieldR = ResolvedField(
            field, contextI.withSpec(Specialization(field.fieldScope, ParameterList.emptyParameterList())),
            scope, MatchScore.zero
        )
        val fieldExpr = ResolvedGetFieldExpression(ownerR, fieldR, scope, origin)
        val nullExpr = SpecialValueExpression(SpecialValue.NULL, scope, origin)
        val isNull = CheckEqualsOp(fieldExpr, nullExpr, byPointer = true, negated = false, null, scope, origin)
        val messageExpr = StringExpression("${base.toString(5)} cannot be null", scope, origin)
        val thrownScope = Types.NullPointerException.clazz
        val thrownConstr = ConstructorResolver.findMemberInScopeImpl(
            thrownScope, "NullPointerException", emptyList(),
            listOf(ValueParameterImpl(null, Types.String, false)), contextI, origin
        ) ?: error("Missing constructor NullPointerException(String)")
        val thrownExpr = ResolvedCallExpression(null, null, thrownConstr, listOf(messageExpr), scope, origin)
        val throwExpr = ThrowExpression(thrownExpr, scope, origin)

        val assignment = ResolvedSetFieldExpression(ownerR, fieldR, base, scope, origin)
        val value = IfElseBranch(isNull, throwExpr, fieldExpr, addToScope = false)
        return ExpressionList(listOf(assignment, value), scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(value)
    }
}