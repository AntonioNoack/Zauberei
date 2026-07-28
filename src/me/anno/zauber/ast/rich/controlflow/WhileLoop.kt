package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.simple.ASTSimplifier.unitInstance
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class WhileLoop(val condition: Expression, val body: Expression, val label: String?, val elseBranch: Expression?) :
    Expression(condition.scope, condition.origin) {

    override fun toStringImpl(depth: Int): String {
        val labelOrEmpty = if (label != null) "$label@" else ""
        var base = "${labelOrEmpty}while(${condition.toString(depth)}) { ${body.toString(depth)} }"
        if (elseBranch != null) {
            base += " else { $elseBranch }"
        }
        return base
    }

    override fun resolveValueType(context: ResolutionContext): Type = exprHasNoType(context)
    override fun resolveThrownType(context: ResolutionContext): Type = unionTypes(
        condition.resolveThrownType(context.withTargetType(Types.Boolean)),
        body.resolveThrownType(context.withTargetType(null)),
        elseBranch?.resolveThrownType(context.withTargetType(null)) ?: Types.Nothing,
    )

    override fun resolveYieldedType(context: ResolutionContext): Type = unionTypes(
        condition.resolveYieldedType(context.withTargetType(Types.Boolean)),
        body.resolveYieldedType(context.withTargetType(null)),
        elseBranch?.resolveYieldedType(context.withTargetType(null)) ?: Types.Nothing,
    )

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false // this has no return value
    override fun needsBackingField(methodScope: Scope): Boolean = condition.needsBackingField(methodScope) ||
            body.needsBackingField(methodScope) ||
            (elseBranch?.needsBackingField(methodScope) ?: false)

    override fun clone(scope: Scope) =
        WhileLoop(condition.clone(scope), body.clone(body.scope), label, elseBranch?.clone(elseBranch.scope))

    // todo while-loop without break can enforce a condition, too
    override fun splitsScope(): Boolean = false
    override fun isResolved(): Boolean =
        condition.isResolved() && body.isResolved() && (elseBranch?.isResolved() ?: true)

    override fun resolveImpl(context: ResolutionContext) =
        WhileLoop(condition.resolve(context), body.resolve(context), label, elseBranch?.resolve(context))

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(condition)
        callback(body)
        if (elseBranch != null) callback(elseBranch)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {

        val graph = block0.graph
        val conditionBlock = block0.nextOrSelfIfEmpty()
        val bodyBlock = graph.addBlock()
        val afterBlock = graph.addBlock()
        val elseBlock = if (elseBranch != null) graph.addBlock() else null

        val labelScope = body.scope
        graph.breakLabels[labelScope] = afterBlock
        graph.continueLabels[labelScope] = conditionBlock

        val unit = unitInstance(graph, this)
        val beforeFlow0 = flow0.withValue(unit, conditionBlock)
        // add condition and jump to insideBlock
        // (condition, beforeBlock1)
        val beforeBlock1 = condition.simplifyTo(context, Types.Boolean, conditionBlock, beforeFlow0, true)
        val beforeBlock1v = beforeBlock1.value ?: return beforeBlock1
        val condition = beforeBlock1v.value
        beforeBlock1v.block.branchCondition = condition.use()
        beforeBlock1v.block.ifBranch = bodyBlock
        beforeBlock1v.block.elseBranch = elseBlock ?: afterBlock

        val elseBlock1 = if (elseBlock != null) {
            // todo test this
            val elseBlock1 = elseBranch!!.simplifyTo(context, null, elseBlock, beforeBlock1, false)
            elseBlock1.value?.block?.nextBranch = afterBlock
            elseBlock1
        } else null

        // add body to insideBlock
        val insideFlow0 = beforeBlock1.withValue(unit, bodyBlock)
        val insideBlock1 = body.simplifyTo(context, null, bodyBlock, insideFlow0, false)
        // continue, if possible
        insideBlock1.value?.block?.nextBranch = conditionBlock

        return beforeBlock1
            .joinReturnAndThrown(insideBlock1)
            .joinReturnAndThrown(elseBlock1)
            .withValue(unit, afterBlock)
    }

}