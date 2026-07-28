package me.anno.zauber.ast.rich.expression.resolved

import me.anno.zauber.ast.rich.expression.CompareType
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.simple.ASTSimplifier.nativeNumbers
import me.anno.zauber.ast.simple.ASTSimplifier.simplifyCall
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleCompare
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

/**
 * for <, <=, >, >=
 * == and != use CheckEqualsOp
 * */
class ResolvedCompareOp(
    val left: Expression,
    val right: Expression,
    val callable: ResolvedMethod,
    val type: CompareType,
) : Expression(left.scope, left.origin) {

    override fun toStringImpl(depth: Int): String {
        return "(${left.toString(depth)} ${type.symbol} ${right.toString(depth)})"
    }

    override fun resolveValueType(context: ResolutionContext): Type = Types.Boolean

    /** return type is always Boolean*/
    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false
    override fun needsBackingField(methodScope: Scope): Boolean = left.needsBackingField(methodScope) ||
            right.needsBackingField(methodScope)

    override fun clone(scope: Scope) = ResolvedCompareOp(left.clone(scope), right.clone(scope), callable, type)
    override fun splitsScope(): Boolean = false // is resolved -> no reason to split
    override fun isResolved(): Boolean = true
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

        val leftType = callable.resolved.selfType ?: callable.resolved.ownerScope.typeWithArgs2
        val block1 = left.simplifyTo(context, leftType, block0, flow0, true)
        val block1v = block1.value ?: return block1
        val left = block1v.value

        val rightType = callable.resolved.valueParameters.first().type
        val block2 = right.simplifyTo(context, rightType, block1v.block, block1, false)
        val block2v = block2.value ?: return block2
        val right = block2v.value

        if (left.type in nativeNumbers && left.type == right.type) {

            val dst = block2v.block.field(Types.Boolean)
            val instr = SimpleCompare(
                dst, left.use(), right.use(), type,
                left.type, scope, origin
            )
            block2v.block.add(instr)
            return block2.withValue(dst)

        } else {

            // Simplify call, then compare with zero
            val tmpInt = block0.field(Types.Int)
            val block3 = simplifyCall(
                tmpInt, block2v.block, block2, left,
                true, null, listOf(right),
                callable, false, scope, origin
            )
            val block3v = block3.value ?: return block3

            val zeroExpr = NumberExpression("0", scope, origin)
            val zero = block0.field(Types.Int, zeroExpr)
            block3v.block.add(SimpleNumber(zero, zeroExpr))

            val dst = block3v.block.field(Types.Boolean)
            val instr = SimpleCompare(
                dst, block3v.value.use(), zero.use(), type,
                Types.Int, scope, origin
            )
            block3v.block.add(instr)
            return block3.withValue(dst)
        }
    }
}
