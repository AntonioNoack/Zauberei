package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

class ReturnExpression(value: Expression, label: String?, scope: Scope, origin: Long) :
    ExitExpression(value, label, scope, origin) {

    override fun toStringImpl(depth: Int): String {
        return if (label == null) "return ${value.toString(depth)}"
        else "return@$label ${value.toString(depth)}"
    }

    override fun resolveValueType(context: ResolutionContext): Type = Types.Nothing
    override fun resolveThrownType(context: ResolutionContext): Type = value.resolveThrownType(context)
    override fun resolveYieldedType(context: ResolutionContext): Type = value.resolveYieldedType(context)

    override fun clone(scope: Scope) = ReturnExpression(value.clone(scope), label, scope, origin)
    override fun splitsScope(): Boolean = false
    override fun resolveImpl(context: ResolutionContext) =
        ReturnExpression(value.resolve(context), label, scope, origin)

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

        val expectedReturnType = block0.graph.expectedReturnType
        val field = value.simplifyTo(context, expectedReturnType, block0, flow0, true, this)
        val field1v = field.value ?: return field

        val actualReturnType = field1v.value.type.specialize(block0.graph.method0)
        check(isSubTypeOf(expectedReturnType, actualReturnType)) {
            val method = block0.graph.method
            "Expected return value in $method " +
                    "to match $expectedReturnType, got $actualReturnType\n" +
                    "  spec: ${block0.graph.method}\n" +
                    "  at ${resolveOrigin(origin)}"
        }

        return field.joinReturnNoValue(field1v.value.use(), field1v.block)
    }
}