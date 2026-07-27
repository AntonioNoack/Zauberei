package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.TypeExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedSetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.types.Type

/**
 * each assignment is the start of a new sub block,
 *   because we know more about types
 *
 * todo -> if it is a field, or a deep constant field,
 *  we somehow need to register this new field definition
 * */
class AssignmentExpression(val dst: Expression, val src: Expression, val hasValue: Boolean = false) :
    Expression(src.scope, src.origin) {

    override fun toStringImpl(depth: Int): String {
        return "$dst=${src.toString(depth)}"
    }

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false // this has no return type
    override fun resolveValueType(context: ResolutionContext): Type {
        return if (hasValue) resolveField(context).getValueType()
        else exprHasNoType(context)
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return dst.needsBackingField(methodScope) ||
                src.needsBackingField(methodScope)
    }

    override fun clone(scope: Scope): Expression =
        AssignmentExpression(dst.clone(scope), src.clone(scope), hasValue)

    // explicit yes
    override fun splitsScope(): Boolean = true
    override fun isResolved(): Boolean = false

    override fun resolveImpl(context: ResolutionContext): Expression {
        val newValue = src.resolve(context)
        when (val dstExpr = dst) {
            is FieldExpression -> {
                val field = dstExpr.resolveField(context)
                val owner = field.resolveOwnerWithoutLeftSide(scope, origin)
                return ResolvedSetFieldExpression(owner, field, newValue, scope, origin)
            }
            is UnresolvedFieldExpression -> {
                val field = dstExpr.resolveField(context)
                    ?: dstExpr.onMissingField()
                val owner = field.resolveOwnerWithoutLeftSide(scope, origin)
                return ResolvedSetFieldExpression(owner, field, newValue, scope, origin)
            }
            is DotExpression if dstExpr.left is ThisExpression && dstExpr.right is FieldResolvable -> {
                val field = dstExpr.right.resolveField(context)
                    ?: error("Could not resolve field for ${dstExpr.right}")
                val owner = dstExpr.left
                return ResolvedSetFieldExpression(owner, field, newValue, scope, origin)
            }
            is DotExpression if dstExpr.left is FieldResolvable && dstExpr.right is FieldResolvable -> {
                val owner = dstExpr.left.resolve(context)
                val ownerType = owner.resolveValueType(context)
                val field = dstExpr.right.resolveField(context.withSelfType(ownerType))
                    ?: error("Could not resolve field for ${dstExpr.right}")
                return if (!field.resolved.isMutable) {
                    // potentially chained case
                    handleImmutableAssignment(field, owner, newValue, dstExpr.left)
                } else {
                    // normal, simple case
                    ResolvedSetFieldExpression(owner, field, newValue, scope, origin)
                }
            }
            is DotExpression if dstExpr.left is TypeExpression && dstExpr.right is FieldResolvable -> {
                val owner = dstExpr.left.resolve(context)
                val ownerType = owner.resolveValueType(context)
                val field = dstExpr.right.resolveField(context.withSelfType(ownerType))
                    ?: error("Could not resolve field for ${dstExpr.right}")
                check(field.resolved.isMutable) {
                    "Expected ${dstExpr.left}.${field.resolved.name} to be mutable @${resolveOrigin(origin)}"
                }
                return ResolvedSetFieldExpression(owner, field, newValue, scope, origin)
            }
            is DotExpression -> {
                throw NotImplementedError(
                    "Implement assignment to DotExpression (" +
                            "${dstExpr.left.javaClass.simpleName} . " +
                            "${dstExpr.right.javaClass.simpleName})"
                )
            }
            is NamedCallExpression if (dstExpr.name == "get") -> {
                // if (LOGGER.isInfoEnabled) LOGGER.info("Resolving set for $dstExpr")
                return NamedCallExpression(
                    dstExpr.self, "set", emptyList() /* todo can we get name-as-import? */,
                    dstExpr.typeParameters, dstExpr.valueParameters + NamedParameter(newValue),
                    dstExpr.scope, origin
                ).resolve(context)
            }
            else -> throw NotImplementedError("Implement assignment to $dst (${dst.javaClass.simpleName})")
        }
    }

    fun resolveField(context: ResolutionContext): ResolvedField {
        return when (val dstExpr = dst) {
            is FieldResolvable -> {
                dstExpr.resolveField(context)
                    ?: dstExpr.onMissingField()
            }
            is DotExpression if dstExpr.left is ThisExpression && dstExpr.right is FieldResolvable -> {
                dstExpr.right.resolveField(context)
                    ?: error("Could not resolve field for ${dstExpr.right}")
            }
            is DotExpression if dstExpr.left is FieldExpression && dstExpr.right is FieldResolvable -> {
                val owner = dstExpr.left.resolve(context)
                val ownerType = owner.resolveValueType(context)
                dstExpr.right.resolveField(context.withSelfType(ownerType))
                    ?: error("Could not resolve field for ${dstExpr.right}")
            }
            is DotExpression if dstExpr.left is TypeExpression && dstExpr.right is FieldResolvable -> {
                val owner = dstExpr.left.resolve(context)
                val ownerType = owner.resolveValueType(context)
                dstExpr.right.resolveField(context.withSelfType(ownerType))
                    ?: error("Could not resolve field for ${dstExpr.right}")
            }
            is DotExpression -> {
                throw NotImplementedError(
                    "Implement assignment to DotExpression (" +
                            "${dstExpr.left.javaClass.simpleName} . " +
                            "${dstExpr.right.javaClass.simpleName})"
                )
            }
            else -> throw NotImplementedError("Implement assignment to $dst (${dst.javaClass.simpleName})")
        }
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(dst)
        callback(src)
    }

    private fun handleImmutableAssignment(
        field: ResolvedField, // immutable
        owner: Expression, // maybe a mutable field
        newValue: Expression, // new value for field
        leftExpr: Any, // just for debugging
    ): Expression {

        if (field.ownerScope.run { isValueType() || isDataClass() }) {
            return prepareSetterForCopy(owner, field, newValue)
        }

        error(
            "Expected ${leftExpr}.${field.resolved.name} " +
                    "to be mutable at ${resolveOrigin(origin)}"
        )
    }

    private fun prepareSetterForCopy(
        owner: Expression, field: ResolvedField, newValue: Expression,
    ): Expression {
        if (owner is ResolvedGetFieldExpression) {
            if (!owner.field.isMutable) {
                // todo it would be nice if we could do this recursively...
                // handleImmutableAssignment(owner.field, owner.self, newValue, owner.self)
                error(
                    "Expected ${owner.self}.${owner.field.resolved.name}.${field.resolved.name} " +
                            "to be mutable at ${resolveOrigin(origin)}"
                )
            }

            val ownerField = owner.field
            val createNewVector = ResolvedCallExpression(
                owner, null, field.resolveCopyMethod(),
                listOf(newValue), scope, origin
            )
            return ResolvedSetFieldExpression(owner.self, ownerField, createNewVector, scope, origin)
        }

        throw NotImplementedError("Expected $owner to be mutable at ${resolveOrigin(origin)}")
    }
}