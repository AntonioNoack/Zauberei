package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.simple.ASTSimplifier.unitInstance
import me.anno.zauber.ast.simple.ASTSimplifier.voidResult
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.constants.SimpleSpecialValue
import me.anno.zauber.ast.simple.controlflow.Flow
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleInstanceOf.Companion.createSimpleInstanceOf
import me.anno.zauber.ast.simple.fields.SimpleSetLocalField
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.AndType.Companion.andTypes
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class TryCatchBlock(
    val tryBody: Expression, val catches: List<Catch>,
    val finally: Expression?, scope: Scope, origin: Long
) : Expression(scope, origin) {

    override fun resolveValueType(context: ResolutionContext): Type {
        val bodyType = TypeResolution.resolveType(context, tryBody)
        val catchTypes = catches.map {
            TypeResolution.resolveType(context, it.body)
        }.reduceOrNull { a, b -> unionTypes(a, b) }
        return if (catchTypes == null) bodyType
        else unionTypes(bodyType, catchTypes)
    }

    override fun resolveThrownType(context: ResolutionContext): Type {
        val base = tryBody.resolveThrownType(context)
        val fallthrough = andTypes(listOf(base) + catches.map { it.parameter.type.not() })
        return unionTypes(fallthrough, finally?.resolveThrownType(context) ?: Types.Nothing)
    }

    override fun resolveYieldedType(context: ResolutionContext): Type =
        unionTypes(
            tryBody.resolveYieldedType(context),
            unionTypes(
                unionTypes(catches.map { it.body.resolveYieldedType(context) }),
                finally?.resolveYieldedType(context) ?: Types.Nothing,
            )
        )

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        return tryBody.hasLambdaOrUnknownGenericsType(context) ||
                catches.any { it.body.hasLambdaOrUnknownGenericsType(context) }
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return tryBody.needsBackingField(methodScope) ||
                catches.any { it.body.needsBackingField(methodScope) } ||
                finally?.needsBackingField(methodScope) == true
    }

    // already a split on its own, or is it?
    override fun splitsScope(): Boolean = false

    override fun clone(scope: Scope) = TryCatchBlock(tryBody.clone(scope), catches.map {
        Catch(
            it.parameter.withScope(it.parameter.scope /* I don't think we should override this */),
            it.body.clone(scope), it.origin
        )
    }, finally?.clone(scope), scope, origin)

    override fun isResolved(): Boolean = tryBody.isResolved() &&
            catches.all { it.parameter.type.isResolved() && it.body.isResolved() } &&
            (finally == null || finally.isResolved())

    override fun resolveImpl(context: ResolutionContext): Expression {
        return TryCatchBlock(tryBody.resolve(context), catches.map {
            Catch(it.parameter, it.body.resolve(context), it.origin)
        }, finally?.resolve(context), scope, origin)
    }

    override fun toStringImpl(depth: Int): String {
        val builder = StringBuilder()
        builder.append("try { ").append(tryBody).append(" }")
        for (catch in catches) {
            builder.append(" catch(${catch.parameter}) { ")
                .append(catch.body)
                .append(" }")
        }
        if (finally != null) {
            builder.append(" finally { ")
                .append(finally).append(" }")
        }
        return builder.toString()
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(tryBody)
        for (catch in catches) {
            callback(catch.body)
        }
        if (finally != null) callback(finally)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {
        check(block0 == flow0.value?.block)
        val body = simplifyImpl(context, tryBody, flow0)
        val allCaught = simplifyCatches(context, this, body, needsValue)
        return simplifyFinally(context, this, allCaught)
    }

    private fun simplifyCatches(
        context: ResolutionContext,
        expr: TryCatchBlock,
        flow0: FlowResult,
        needsValue: Boolean
    ): FlowResult {
        var blockI = flow0
        for (catch in expr.catches) {
            blockI = simplifyCatch(context, expr, catch, blockI, needsValue)
        }
        return blockI
    }

    private fun simplifyCatch(
        context: ResolutionContext,
        expr: TryCatchBlock,
        catch: Catch,
        flow0: FlowResult,
        needsValue: Boolean
    ): FlowResult {

        val block0 = flow0.thrown ?: return flow0
        val block0b = block0.block

        val thrownType = catch.parameter.type
        val instanceOfField = block0b.field(Types.Boolean)
        val instanceCheck = createSimpleInstanceOf(block0b, instanceOfField, block0.value, thrownType, expr.scope, catch.origin)

        val alwaysHandled = instanceCheck is SimpleSpecialValue && instanceCheck.type == SpecialValue.TRUE
        val neverHandled = instanceCheck is SimpleSpecialValue && instanceCheck.type == SpecialValue.FALSE
        println("Catch handling($instanceCheck by ${block0.value} is $thrownType), body: ${catch.body}")

        return when {
            alwaysHandled -> simplifyCatchHandleBranch(context, expr, catch, needsValue, block0, block0b)
            neverHandled -> simplifyCatchContinueBranch(flow0, block0, block0b)
            else -> {
                // instance-check was already added

                val graph = block0b.graph
                val ifBlock = graph.addBlock()
                val elseBlock = graph.addBlock()
                block0b.branchCondition = instanceOfField.use()
                block0b.ifBranch = ifBlock
                block0b.elseBranch = elseBlock

                val ifFlow = simplifyCatchHandleBranch(context, expr, catch, needsValue, block0, ifBlock)
                val elseFlow = simplifyCatchContinueBranch(flow0, block0, elseBlock)
                ifFlow.joinWith(elseFlow)
            }
        }
    }

    private fun simplifyCatchHandleBranch(
        context: ResolutionContext,
        expr: TryCatchBlock,
        catch: Catch,
        needsValue: Boolean,
        block0: Flow,
        ifBlock: SimpleBlock,
    ): FlowResult {
        val ifFlow = FlowResult(Flow(unitInstance(ifBlock.graph, expr), ifBlock), null, null)
        val thrownField = catch.parameter.getOrCreateField(null, Flags.NONE)
        val thrownLocalField = block0.block.graph.getOrPutLocalField(thrownField, context)
        ifBlock.add(SimpleSetLocalField(thrownLocalField, block0.value, expr.scope, expr.origin))
        return catch.body.simplifyTo(context, context.targetType, ifBlock, ifFlow, needsValue)
    }

    private fun simplifyCatchContinueBranch(
        flow0: FlowResult,
        block0: Flow,
        elseBlock: SimpleBlock,
    ): FlowResult {
        return flow0.withThrown(block0.value, elseBlock)
    }

    private fun simplifyFinally(
        context: ResolutionContext,
        expr: TryCatchBlock,
        flow0: FlowResult
    ): FlowResult {

        val finally = expr.finally ?: return flow0

        // apply finally-block to normal execution flow
        val value = if (flow0.value != null) {
            simplifyImpl(context, finally, flow0)
                .withValue(flow0.value.value)
        } else voidResult

        // apply finally-block to returned values
        val returned = if (flow0.returned != null) {
            val returnFlow = flow0.returnToValue()
            simplifyImpl(context, finally, returnFlow)
                .valueToReturn(flow0.returned)
        } else null

        // apply finally-block to thrown values
        val thrown = if (flow0.thrown != null) {
            val throwFlow = flow0.thrownToValue()
            simplifyImpl(context, finally, throwFlow)
                .valueToThrown(flow0.thrown)
        } else null

        return value
            .joinReturnAndThrown(returned)
            .joinReturnAndThrown(thrown)
    }

    private fun simplifyImpl(context: ResolutionContext, expr: Expression, flow0: FlowResult): FlowResult {
        val block0 = flow0.value ?: return flow0
        return expr.simplifyTo(context, context.targetType, block0.block, flow0, needsValue = false)
    }

}