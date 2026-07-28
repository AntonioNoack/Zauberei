package me.anno.zauber.ast.rich.expression.resolved

import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.ASTSimplifier.simplifyCall
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.ResolvedConstructor
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Type

class ResolvedCallExpression(
    selfExpr0: Expression?,
    val thisExpr: Expression?,
    val callable: ResolvedMember<out MethodLike>,
    val valueParameters: List<Expression>,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    val selfExpr: Expression? =
        if (callable is ResolvedConstructor) selfExpr0 as? SuperExpression
        else selfExpr0 ?: callable.getBaseIfMissing(scope, origin)

    init {
        check(valueParameters.all { it.isResolved() })
        when (callable) {
            is ResolvedMethod, is ResolvedField -> {
                // these must have an owner
                check(this.selfExpr != null) { "Expected self to not be null for $callable" }
            }
            is ResolvedConstructor -> {
                // in inner classes, self is passed by the first value parameter
                // check(this.self == null) { "Expected self to be null for $callable, got $self" }
                // self may be SuperExpression
            }
            else -> throw NotImplementedError()
        }
        assertEquals(
            callable is ResolvedMethod &&
                    callable.resolved.hasExplicitSelfType, thisExpr != null
        ) { "Explicit-self mismatch: $callable vs $thisExpr" }
    }

    val context get() = callable.context

    override fun clone(scope: Scope) = ResolvedCallExpression(
        this.selfExpr?.clone(scope), thisExpr?.clone(scope), callable,
        valueParameters.map { it.clone(scope) },
        scope, origin
    )

    override fun needsBackingField(methodScope: Scope): Boolean {
        return (this.selfExpr != null && this.selfExpr.needsBackingField(methodScope)) ||
                valueParameters.any { it.needsBackingField(methodScope) }
    }

    override fun resolveValueType(context: ResolutionContext): Type = callable.resolveValueType()
    override fun resolveThrownType(context: ResolutionContext): Type = callable.resolveThrownType()
    override fun resolveYieldedType(context: ResolutionContext): Type = callable.resolveYieldedType()

    override fun splitsScope(): Boolean = false
    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false
    override fun isResolved(): Boolean = true

    override fun toStringImpl(depth: Int): String {
        val base =
            if (this.selfExpr != null) "(${this.selfExpr.toString(depth)})." else ""
        val valueParameters = valueParameters.joinToString(", ", "(", ")") { it.toString(depth) }
        val typeParameters = callable.specialization.typeParameters
        val name = when (val m = callable.resolved) {
            is Method -> m.name
            is Field -> m.name
            is Constructor -> m.classScope.name
            else -> throw NotImplementedError()
        }
        return if (typeParameters.isEmpty()) {
            "$base$name$valueParameters"
        } else {
            "$base$name${typeParameters.joinToString(", ", "<", ">")}$valueParameters"
        }
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        if (this.selfExpr != null) callback(this.selfExpr)
        for (param in valueParameters) callback(param)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {

        // (base, block1)
        val selfTargetType = callable.selfType// ?: callable.resolved.ownerScope.typeWithArgs2
        val block1 = selfExpr?.simplifyTo(context, selfTargetType, block0, flow0, true, contextExpr = this) ?: flow0
        val base = block1.value ?: return block1

        // println("Simplified self to ${expr.self} (${expr.self.javaClass.simpleName})")
        var blockI = block1
        val valueParameters = valueParameters.mapIndexed { index, param ->
            blockI = param.simplifyTo(
                context, callable.resolved.valueParameters[index].type,
                blockI.value!!.block, blockI, false, contextExpr = this
            )
            blockI.value?.value ?: return blockI
        }

        val thisExpr = if (thisExpr != null) {
            blockI = thisExpr.simplifyTo(
                context, callable.resolved.ownerScope.typeWithArgs2,
                blockI.value!!.block, blockI, false, contextExpr = this
            )
            blockI.value?.value ?: return blockI
        } else null

        val method = callable
        val selfExpr = if (selfExpr != null) base.value.use() else null
        val dst = block0.field(method.resolveValueType())
        val isSuper = this.selfExpr is SuperExpression
        return simplifyCall(
            dst, blockI.value!!.block, blockI, selfExpr,
            !isSuper, thisExpr,
            valueParameters, method,
            method is ResolvedConstructor && !isSuper,
            scope, origin
        )
    }

}