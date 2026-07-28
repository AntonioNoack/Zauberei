package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.simple.ASTSimplifier.handleThrown
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleDynamicMacro
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.GenericType

/**
 * a macro inside a macro,
 * only supported at compile-time (or within the interpreter) of course
 * */
class DynamicMacroExpression(
    val self: Expression,
    val method: ResolvedMethod,
    val valueParameters: List<Expression>,
    val imports: List<Import>,
    val generics: HashMap<String, GenericType>,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    override fun isResolved(): Boolean = self.isResolved() && valueParameters.all { it.isResolved() }
    override fun splitsScope(): Boolean = self.splitsScope() || valueParameters.any { it.splitsScope() }

    override fun resolveValueType(context: ResolutionContext): Type =
        method.resolved.resolveReturnType(context)

    override fun clone(scope: Scope) = DynamicMacroExpression(
        self.clone(scope), method,
        valueParameters.map { it.clone(scope) },
        imports, generics,
        scope, origin
    )

    override fun toStringImpl(depth: Int): String = "DynamicMacro($method, $valueParameters)"

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        TODO("Not yet implemented")
    }

    // may be more complicated than that... we cannot really check method
    override fun needsBackingField(methodScope: Scope): Boolean =
        self.needsBackingField(methodScope) || valueParameters.any { it.needsBackingField(methodScope) }

    override fun resolveImpl(context: ResolutionContext): Expression {
        val contextI = context.withSelfType(null)
        return DynamicMacroExpression(self.resolve(context), method, valueParameters.mapIndexed { index, it ->
            val targetType = method.resolved.valueParameters[index].type
                .specialize(method.specialization)
            val contextJ = contextI.withTargetType(targetType)
            it.resolve(contextJ)
        }, imports, generics, scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        for (param in valueParameters) {
            callback(param)
        }
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {

        // (base, block1)
        val block1 = self.simplifyTo(context, context.targetType, block0, flow0, true)
        val base = block1.value ?: return block1

        // println("Simplified self to ${expr.self} (${expr.self.javaClass.simpleName})")
        var blockI = block1
        val valueParameters = valueParameters.mapIndexed { index, param ->
            val targetType = method.resolved.valueParameters[index].type
            blockI = param.simplifyTo(context, targetType, blockI.value!!.block, blockI, false)
            blockI.value?.value ?: return blockI
        }

        valueParameters.forEach { it.use() }

        val method0 = method
        val method = method0.resolved
        val block0 = blockI.value!!.block
        val selfExpr = base.value.use()
        // then execute it
        val dst = block0.field(method0.resolveValueType())
        val specialization = method0.specialization
        val call = SimpleDynamicMacro(dst, this, selfExpr.use(), valueParameters, scope, origin)
        return handleThrown(block0, flow0, dst, call, method.getThrownType(specialization))
    }
}