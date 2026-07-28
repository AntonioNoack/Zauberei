package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class ThrowExpression(value: Expression, scope: Scope, origin: Long) :
    ExitExpression(value, null, scope, origin) {

    override fun toStringImpl(depth: Int): String {
        return "throw ${value.toString(depth)}"
    }

    override fun resolveValueType(context: ResolutionContext): Type = Types.Nothing
    override fun resolveThrownType(context: ResolutionContext): Type {
        // if value returns or throws, we throw
        return unionTypes(value.resolveValueType(context), value.resolveThrownType(context))
    }

    override fun resolveYieldedType(context: ResolutionContext): Type = value.resolveYieldedType(context)

    override fun clone(scope: Scope) = ThrowExpression(value.clone(scope), scope, origin)
    override fun splitsScope(): Boolean = false
    override fun resolveImpl(context: ResolutionContext) =
        ThrowExpression(value.resolve(context), scope, origin)

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
        val field = value.simplifyTo(context, Types.Throwable, block0, flow0, true)
        val field1v = field.value ?: return field
        return field.joinThrownNoValue(field1v.value.use(), field1v.block)
    }
}