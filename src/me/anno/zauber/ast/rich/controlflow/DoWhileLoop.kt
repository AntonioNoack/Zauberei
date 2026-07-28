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

/**
 * cannot easily be converted to a while-loop, because continue needs to run the evaluation!
 * */
class DoWhileLoop(val body: Expression, val condition: Expression, val label: String?) :
    Expression(condition.scope, condition.origin) {

    override fun toStringImpl(depth: Int): String {
        return "${if (label != null) "$label@" else ""} do { ${body.toString(depth)} } while (${condition.toString(depth)})"
    }

    override fun resolveValueType(context: ResolutionContext): Type = exprHasNoType(context)
    override fun resolveThrownType(context: ResolutionContext): Type = unionTypes(
        condition.resolveThrownType(context.withTargetType(Types.Boolean)),
        body.resolveThrownType(context.withTargetType(null)),
    )

    override fun resolveYieldedType(context: ResolutionContext): Type = unionTypes(
        condition.resolveYieldedType(context.withTargetType(Types.Boolean)),
        body.resolveYieldedType(context.withTargetType(null)),
    )

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false // this has no return value
    override fun needsBackingField(methodScope: Scope): Boolean = condition.needsBackingField(methodScope) ||
            body.needsBackingField(methodScope)

    override fun clone(scope: Scope) =
        DoWhileLoop(body = body.clone(body.scope), condition = condition.clone(scope), label)

    // todo while-loop without break can enforce a condition, too
    override fun splitsScope(): Boolean = false
    override fun isResolved(): Boolean = condition.isResolved() && body.isResolved()
    override fun resolveImpl(context: ResolutionContext) =
        DoWhileLoop(body = body.resolve(context), condition = condition.resolve(context), label)

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(body)
        callback(condition)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {
        val graph = block0.graph
        val bodyBlock = block0.nextOrSelfIfEmpty()
        val conditionBlock = graph.addBlock()
        val afterBlock = graph.addBlock()

        val labelScope = body.scope
        graph.breakLabels[labelScope] = afterBlock
        graph.continueLabels[labelScope] = conditionBlock

        val unit = unitInstance(graph, this)
        val insideFlow0 = flow0.withValue(unit, bodyBlock)
        val insideBlock1 = body.simplifyTo(context, null, bodyBlock, insideFlow0, false)
        var flow1 = insideFlow0.joinReturnAndThrown(insideBlock1)
        if (insideBlock1.value != null ||
            afterBlock.inputBlocks.isNotEmpty() || // there is a @break
            conditionBlock.inputBlocks.isNotEmpty() // there is a @continue
        ) {
            insideBlock1.value?.block?.nextBranch = conditionBlock

            // add condition and jump to insideBlock
            val flow1d = flow1.withValue(unit, conditionBlock)
            val decideBlock1i = condition.simplifyTo(context, Types.Boolean, conditionBlock, flow1d, true)

            if (decideBlock1i.value != null) {
                val decideBlock1 = decideBlock1i.value.block
                decideBlock1.branchCondition = decideBlock1i.value.value.use()
                decideBlock1.ifBranch = bodyBlock
                decideBlock1.elseBranch = afterBlock
            }
            flow1 = flow1.joinReturnAndThrown(decideBlock1i)
        }

        // Unit, because no return value is supported
        return flow1.withValue(unit, afterBlock)
    }
}