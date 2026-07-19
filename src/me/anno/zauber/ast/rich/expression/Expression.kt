package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

abstract class Expression(val scope: Scope, val origin: Long) {

    /**
     * cached for faster future resolution and for checking in from later stages
     * */
    var resolvedType: Type? = null

    abstract fun resolveValueType(context: ResolutionContext): Type
    open fun resolveThrownType(context: ResolutionContext): Type {
        LOGGER.warn("Implement resolveThrownType for ${javaClass.simpleName}")
        return Types.Throwable // just assume all
    }

    open fun resolveYieldedType(context: ResolutionContext): Type {
        LOGGER.warn("Implement resolveYieldedType for ${javaClass.simpleName}")
        return Types.Yielded
    }

    fun resolve(context: ResolutionContext): Expression {
        if (isResolved()) return this
        // println("Resolving expr $this in $context")
        val resolved = resolveImpl(context.withScope(scope))
        // println("Resolved $this to $resolved")
        check(resolved.isResolved()) {
            "Failed to resolve (${javaClass.simpleName}) $this, somehow it is still not resolved"
        }
        // it's fully resolved, so we can infer the type once and for all
        if (resolved.resolvedType == null) {
            resolved.resolvedType = resolved.resolveValueType(context)
        }
        return resolved
    }

    // @Deprecated("Only the Expression-class and nothing else should call this")
    open fun resolveImpl(context: ResolutionContext): Expression {
        if (isResolved()) return this
        throw NotImplementedError("TODO: Resolve ${javaClass.simpleName}, $this")
    }

    fun exprHasNoType(context: ResolutionContext): Type {
        if (!context.allowTypeless) error(
            "Expected type, but found $this (${javaClass.simpleName}) in ${resolveOrigin(origin)}"
        )
        return Types.Unit
    }

    init {
        numExpressionsCreated++
    }

    /**
     * clone to get rid of resolvedType,
     * or to change the scope
     * */
    abstract fun clone(scope: Scope): Expression

    override fun toString(): String = toStringImpl(10)
    fun toString(depth: Int): String {
        return if (depth >= 0) toStringImpl(depth - 1) else "${javaClass.simpleName}..."
    }

    abstract fun toStringImpl(depth: Int): String

    /**
     * returns whether the type of this has a lambda, or some other unknown generics inside;
     * for lambdas, we need to know, because usually no other type information is available;
     * for unknown generics, we need them for the return type to be fully known
     * */
    abstract fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean

    /**
     * whether the expression contains a FieldExpression with name == 'field' and scope == methodScope
     * */
    abstract fun needsBackingField(methodScope: Scope): Boolean

    /**
     * whether this expression changes information about types
     * */
    abstract fun splitsScope(): Boolean

    /**
     * after type resolution, all expressions should be resolved
     * */
    abstract fun isResolved(): Boolean

    abstract fun forEachExpression(callback: (Expression) -> Unit)
    fun forEachExpressionRecursively(callback: (Expression) -> Unit) {
        forEachExpression { expr ->
            expr.forEachExpressionRecursively(callback)
            callback(expr)
        }
    }

    open fun replaceLambdaFieldsWithClassFields(oldFields: List<Field>, newFields: List<Field>): Expression {
        if (oldFields.isEmpty()) return this
        throw NotImplementedError("Replace fieldExpressions in (${javaClass.simpleName}) $this from $oldFields to $newFields")
    }

    open fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock, flow0: FlowResult, needsValue: Boolean,
        contextExpr: Expression? = null// for ThisExpression
    ): FlowResult {
        if (!isResolved()) error("${javaClass.simpleName} was not resolved")
        throw NotImplementedError("Simplify value ${javaClass.simpleName}: $this")
    }

    fun implicitCastTo(targetParam: Parameter, context: ResolutionContext): Expression {
        val valueType = resolveValueType(context)
        if (valueType !is ClassType) return this

        val targetType = targetParam.type.resolvedName.specialize(context)
        val implicitMap = (valueType.clazz).implicitCastMethods[targetType] ?: return this

        val newSpec = context.specialization.withScope(implicitMap.scope)
        val resolvedImplicitMap = ResolvedMethod(implicitMap, context.withSpec(newSpec), scope, MatchScore.zero)
        return ResolvedCallExpression(this, null, resolvedImplicitMap, emptyList(), scope, origin)
    }

    companion object {
        private val LOGGER = LogManager.getLogger(Expression::class)

        var numExpressionsCreated = 0
            private set
    }
}