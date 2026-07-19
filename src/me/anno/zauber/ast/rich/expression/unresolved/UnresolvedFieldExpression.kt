package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.FieldResolver
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.NonObjectClassType

class UnresolvedFieldExpression(
    val name: String,
    val nameAsImport: List<Import>,
    scope: Scope, origin: Long
) : Expression(scope, origin), FieldResolvable {

    override fun toStringImpl(depth: Int): String = name
    override fun clone(scope: Scope) = UnresolvedFieldExpression(name, nameAsImport, scope, origin)
    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false

    // todo what if 'field' is shadowed?
    override fun needsBackingField(methodScope: Scope): Boolean = name == "field"

    override fun resolveField(context: ResolutionContext): ResolvedField? {
        return FieldResolver.resolveField(context, scope, name, nameAsImport, null, origin)
    }

    override fun onMissingField(): Nothing {
        error("Failed to resolve field $name in $scope at ${resolveOrigin(origin)}")
    }

    override fun splitsScope(): Boolean = false
    override fun isResolved(): Boolean = false

    override fun resolveValueType(context: ResolutionContext): Type {
        val field = resolveField(context)
        if (field != null) return field.getValueType()

        val type0 = try {
            scope.resolveType(name, nameAsImport)
                .specialize(context)
        } catch (_: Exception) {
            error("Failed to resolve field '$name' in $scope\n  at ${resolveOrigin(origin)}")
        }
        check(type0 is ClassType) { "Expected $type0 from $this to be ClassType" }
        return NonObjectClassType(type0)
    }

    // todo this would be a getter by default... resolve its type...
    override fun resolveThrownType(context: ResolutionContext): Type = Types.Nothing
    override fun resolveYieldedType(context: ResolutionContext): Type = Types.Nothing

    override fun resolveImpl(context: ResolutionContext): ResolvedGetFieldExpression {
        val field = resolveField(context) ?: onMissingField()
        val owner = field.resolveOwnerWithoutLeftSide(scope, origin)
        return ResolvedGetFieldExpression(owner, field, scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {}

    // will be replaced later on
    override fun replaceLambdaFieldsWithClassFields(oldFields: List<Field>, newFields: List<Field>): Expression {
        if (newFields.none { it.name == name }) return this
        return UnresolvedFieldExpression(name, nameAsImport, newFields.first().scope, origin)
    }
}