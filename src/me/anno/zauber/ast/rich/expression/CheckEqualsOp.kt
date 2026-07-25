package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.unresolved.NamedCallExpression
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleCheckEquals
import me.anno.zauber.ast.simple.expression.SimpleCheckIdentical
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class CheckEqualsOp(
    val left: Expression, val right: Expression,
    val byPointer: Boolean, val negated: Boolean,
    val resolved: ResolvedCallExpression?,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    val symbol: String
        get() = when {
            byPointer && negated -> "!=="
            byPointer -> "==="
            negated -> "!="
            else -> "=="
        }

    override fun toStringImpl(depth: Int): String {
        return "(${left.toString(depth)})$symbol(${right.toString(depth)})"
    }

    override fun resolveValueType(context: ResolutionContext): Type = Types.Boolean
    override fun resolveThrownType(context: ResolutionContext): Type {
        val context1 = context.withTargetType(null)
        if (byPointer) return unionTypes(left.resolveThrownType(context1), right.resolveThrownType(context1))
        TODO("check method itself")
    }

    override fun resolveYieldedType(context: ResolutionContext): Type {
        val context1 = context.withTargetType(null)
        if (byPointer) return unionTypes(left.resolveYieldedType(context1), right.resolveYieldedType(context1))
        TODO("check method itself")
    }

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false // result is always Boolean
    override fun needsBackingField(methodScope: Scope): Boolean {
        return left.needsBackingField(methodScope) || right.needsBackingField(methodScope)
    }

    override fun splitsScope(): Boolean {
        return left.splitsScope() || right.splitsScope()
    }

    override fun clone(scope: Scope) =
        CheckEqualsOp(left.clone(scope), right.clone(scope), byPointer, negated, resolved, scope, origin)

    override fun isResolved(): Boolean = left.isResolved() && right.isResolved() && (byPointer || resolved != null)
    override fun resolveImpl(context: ResolutionContext): Expression {
        val resolved = resolved ?: if (!byPointer) {
            NamedCallExpression(NotNullExpression(left), "equals", right, scope, origin)
                .resolve(context) as ResolvedCallExpression
        } else null

        return CheckEqualsOp(left.resolve(context), right.resolve(context), byPointer, negated, resolved, scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {

        val block1 = left.simplify(context, block0, flow0, true)
        val block1v = block1.value ?: return block1

        val block2 = right.simplify(context, block1v.block, block1, true)
        val block2v = block2.value ?: return block2

        val dst = block2v.block.field(Types.Boolean)
        val left = block1v.value
        val right = block2v.value
        val call = if (byPointer) {
            SimpleCheckIdentical(
                dst, left.use(), right.use(),
                negated, scope, origin
            )
        } else {
            // a == null ? b == null : b != null ? a.equals(b) : false
            // todo inline this call if both sides are non-nullable??
            /*if(left.type.mayBeNull() || right.type.mayBeNull()) {

            }*/
            SimpleCheckEquals(
                dst, left.use(), right.use(),
                negated, resolved!!.callable as ResolvedMethod,
                scope, origin
            )
            // todo handle error
        }
        block2v.block.add(call)
        return flow0
            .joinReturnAndThrown(block1)
            .joinReturnAndThrown(block2)
            .withValue(dst, block2v.block)
    }
}