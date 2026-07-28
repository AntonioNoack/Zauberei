package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleInstanceOf.Companion.createSimpleInstanceOf
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

class IsInstanceOfExpr(val value: Expression, val type: Type, scope: Scope, origin: Long) :
    Expression(scope, origin) {

    val symbol: String get() = "is"

    override fun toStringImpl(depth: Int): String {
        return "(${value.toString(depth)})is($type)"
    }

    override fun resolveValueType(context: ResolutionContext): Type = Types.Boolean
    override fun resolveThrownType(context: ResolutionContext): Type = value.resolveThrownType(context)
    override fun resolveYieldedType(context: ResolutionContext): Type = value.resolveYieldedType(context)

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false // always boolean
    override fun needsBackingField(methodScope: Scope): Boolean = value.needsBackingField(methodScope)

    override fun clone(scope: Scope): IsInstanceOfExpr {
        return IsInstanceOfExpr(value.clone(scope), type, scope, origin)
    }

    // can change type information...
    override fun splitsScope(): Boolean = true
    override fun isResolved(): Boolean = value.isResolved() && type.isResolved()
    override fun resolveImpl(context: ResolutionContext): Expression {
        return IsInstanceOfExpr(value.resolve(context), type.resolve(), scope, origin)
    }

    fun withValue(newValue: Expression): IsInstanceOfExpr {
        return IsInstanceOfExpr(newValue, type, scope, origin)
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
        val block1 = value.simplifyTo(context, context.targetType, block0, flow0, true)
        val block1v = block1.value ?: return block1
        val block1b = block1v.block
        val dst = block1b.field(Types.Boolean)
        createSimpleInstanceOf(block1b, dst, block1v.value.use(), type, scope, origin)
        return block1.withValue(dst, block1v.block)
    }

}