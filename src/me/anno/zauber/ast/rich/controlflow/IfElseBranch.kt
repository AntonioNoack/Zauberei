package me.anno.zauber.ast.rich.controlflow

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.simple.ASTSimplifier.unitInstance
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.BooleanUtils.not
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class IfElseBranch(
    val condition: Expression, val ifBranch: Expression, val elseBranch: Expression?,
    addToScope: Boolean = true
) : Expression(condition.scope, condition.origin) {

    init {
        check(
            ifBranch.scope != elseBranch?.scope ||
                    ifBranch.isResolved() && elseBranch.isResolved()
        ) {
            "IfBranch and ElseBranch must have different scopes. ${ifBranch.scope}, " +
                    "at ${resolveOrigin(condition.origin)}"
        }
        check(
            ifBranch.scope != condition.scope ||
                    ifBranch is SpecialValueExpression ||
                    ifBranch.isResolved() && condition.isResolved()
        ) {
            "If and condition somehow have the same scope: ${condition.scope.pathStr}, " +
                    "at ${resolveOrigin(condition.origin)}"
        }
        check(
            elseBranch?.scope != condition.scope ||
                    elseBranch is SpecialValueExpression ||
                    elseBranch.isResolved() && condition.isResolved()
        ) {
            "Else and condition somehow have the same scope: ${condition.scope.pathStr}, " +
                    "at ${resolveOrigin(condition.origin)}"
        }

        if (addToScope) {
            ifBranch.scope.addCondition(condition)
            elseBranch?.scope?.addCondition(condition.not())
        }
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        return if (elseBranch == null) {
            exprHasNoType(context)
        } else {
            // targetLambdaType stays the same
            val ifType = TypeResolution.resolveType(context, ifBranch)
            val elseType = TypeResolution.resolveType(context, elseBranch)
            unionTypes(ifType, elseType)
        }
    }

    override fun resolveThrownType(context: ResolutionContext): Type {
        return unionTypes(ifBranch.resolveThrownType(context), elseBranch?.resolveThrownType(context) ?: Types.Nothing)
    }

    override fun resolveYieldedType(context: ResolutionContext): Type {
        return unionTypes(
            ifBranch.resolveYieldedType(context),
            elseBranch?.resolveYieldedType(context) ?: Types.Nothing
        )
    }

    override fun clone(scope: Scope): Expression = IfElseBranch(
        condition.clone(scope),
        ifBranch.clone(ifBranch.scope),
        elseBranch?.clone(elseBranch.scope),
        false
    )

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        return elseBranch != null && // if else is undefined, this has no return type
                (ifBranch.hasLambdaOrUnknownGenericsType(context) ||
                        elseBranch.hasLambdaOrUnknownGenericsType(context))
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return condition.needsBackingField(methodScope) ||
                ifBranch.needsBackingField(methodScope) ||
                elseBranch?.needsBackingField(methodScope) == true
    }

    // todo if-else-branch can enforce a condition: if only one branch returns
    override fun splitsScope(): Boolean = false

    override fun isResolved(): Boolean = condition.isResolved() &&
            ifBranch.isResolved() &&
            (elseBranch == null || elseBranch.isResolved())

    override fun resolveImpl(context: ResolutionContext): Expression {
        return IfElseBranch(
            condition.resolve(context),
            ifBranch.resolve(context),
            elseBranch?.resolve(context),
        )
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(condition)
        callback(ifBranch)
        if (elseBranch != null) callback(elseBranch)
    }

    override fun toStringImpl(depth: Int): String {
        return if (elseBranch == null) {
            "if(${condition.toString(depth)}) { ${ifBranch.toString(depth)} }"
        } else {
            "if(${condition.toString(depth)}) { ${ifBranch.toString(depth)} } else { ${elseBranch.toString(depth)} }"
        }
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {

        val block1 = condition.simplifyTo(context, Types.Boolean, block0, flow0, true)
        val block1v = block1.value ?: return block1
        val condition = block1v.value

        // todo when the condition to a branch is a simple boolean, skip evaluating the other branch!

        val graph = block0.graph
        val ifBlock = graph.addBlock()
        val elseBlock = graph.addBlock()

        block1v.block.branchCondition = condition.use()
        block1v.block.ifBranch = ifBlock
        block1v.block.elseBranch = elseBlock

        val unit = unitInstance(graph, this)
        val ifFlow = block1.withValue(unit, ifBlock)
        val elseFlow = block1.withValue(unit, elseBlock)

        if (elseBranch == null) {
            val ifValue = ifBranch.simplifyTo(context, context.targetType, ifBlock, ifFlow, needsValue)
            ifValue.value?.block?.nextBranch = elseBlock

            return elseFlow
                .joinReturnAndThrown(ifValue)
                .withValue(unit, elseBlock)
        } else {
            val ifValue = ifBranch.simplifyTo(context, context.targetType, ifBlock, ifFlow, needsValue)
            val elseValue = elseBranch.simplifyTo(context, context.targetType, elseBlock, elseFlow, needsValue)
            return ifValue.joinWith(elseValue)
        }
    }
}