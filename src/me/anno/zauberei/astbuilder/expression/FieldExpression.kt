package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.members.MemberResolver.Companion.findGenericsForMatch
import me.anno.zauberei.typeresolution.members.ResolvedField
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class FieldExpression(
    val field: Field,
    scope: Scope, origin: Int
) : Expression(scope, origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = field.toString()
    override fun clone(scope: Scope) = FieldExpression(field, scope, origin)
    override fun hasLambdaOrUnknownGenericsType(): Boolean = false

    override fun resolveType(context: ResolutionContext): Type {
        val generics = findGenericsForMatch(
            field.selfType, context.selfType,
            field.valueType, context.targetType,
            emptyList(), emptyList(),
            emptyList(), emptyList()
        )
        check(generics != null)
        val resolved = ResolvedField(generics, field, emptyList(), context)
        return resolved.getValueType()
    }
}