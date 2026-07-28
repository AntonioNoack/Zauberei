package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleGetTypeFromInstance
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Type.Companion.and
import me.anno.zauber.types.Types

class GetClassFromValueExpression(val value: Expression, scope: Scope, origin: Long) : Expression(scope, origin) {

    override fun toStringImpl(depth: Int): String {
        return "${value.toString(depth)}::class"
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        val type = value.resolveValueType(context)
        return Types.ClassType.withTypeParameter(type)
    }

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean =
        value.hasLambdaOrUnknownGenericsType(context)

    override fun needsBackingField(methodScope: Scope): Boolean = value.needsBackingField(methodScope)
    override fun splitsScope(): Boolean = value.splitsScope()
    override fun isResolved(): Boolean = value.isResolved()

    override fun clone(scope: Scope) = GetClassFromValueExpression(value, scope, origin)
    override fun resolveImpl(context: ResolutionContext): Expression {
        return GetClassFromValueExpression(value.resolve(context), scope, origin)
    }

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
        val targetType = context.targetType.and(Types.TypeT)
        val block1 = value.simplifyTo(context, targetType, block0, flow0, true)
        val block1v = block1.value ?: return block1
        val dst = block0.field(resolveValueType(context))
        block0.add(SimpleGetTypeFromInstance(dst, block1v.value.use(), scope, origin))
        return flow0.withValue(dst, block0)
    }

}