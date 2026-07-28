package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.simple.ASTSimplifier.unitInstance
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.controlflow.SimpleYield
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type

/**
 * any method can yield:
 *   a yield can return extra instructions (like sleeping / waiting on resources),
 *   or return extra values (like intermediate state)
 * using the new keyword async,
 *   we get back any yielded thing,
 *   and can decide whether we accept it
 *   -> Yieldable: Returned | Thrown | Yielded
 * todo by default, any yield-method
 *   - a) always stores all its state in a struct
 *   - b) contains a lambda for continuation
 *   - c) if normally called, yields are redirected to the method above (like throw/return),
 *        until the method "yields" a result
 * todo we could implement throws using these :3
 *   - and both return and throw are just special values that must call finish(),
 *   - dropping/GC-ing a Yieldable calls finish(), too
 * */
class YieldExpression(value: Expression, scope: Scope, origin: Long) :
    ExitExpression(value, null, scope, origin) {

    override fun toStringImpl(depth: Int): String {
        return "yield ${value.toString(depth)}"
    }

    override fun resolveValueType(context: ResolutionContext): Type = exprHasNoType(context)
    override fun resolveYieldedType(context: ResolutionContext): Type = value.resolveValueType(context)
    override fun resolveThrownType(context: ResolutionContext): Type = value.resolveThrownType(context)

    override fun clone(scope: Scope) = YieldExpression(value.clone(scope), scope, origin)
    override fun splitsScope(): Boolean = true // yes, this even stops execution
    override fun resolveImpl(context: ResolutionContext): Expression =
        YieldExpression(value.resolve(context), scope, origin)

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(value)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {
        val field = value.simplifyTo(context, null, block0, flow0, true)
        val field1v = field.value ?: return field
        val continueBlock = block0.graph.addBlock()
        field1v.block.add(SimpleYield(field1v.value.use(), continueBlock, scope, origin))
        continueBlock.isEntryPoint = true
        return field.withValue(unitInstance(block0.graph, this), continueBlock)
    }
}