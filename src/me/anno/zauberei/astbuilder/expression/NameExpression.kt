package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.findType
import me.anno.zauberei.typeresolution.members.FieldResolver.resolveField
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class NameExpression(
    val name: String,
    scope: Scope, origin: Int
) : Expression(scope, origin) {

    companion object {
        fun nameExpression(name: String, origin: Int, astBuilder: ASTBuilder, scope: Scope): Expression {
            // todo check whether something is in front (some sort of period/accessor)
            val isChild = when {
                astBuilder.tokens.equals(origin - 1, ".") ||
                        astBuilder.tokens.equals(origin - 1, "?.") -> true
                else -> false
            }
            return if (isChild) NameExpression(name, scope, origin)
            else {
                val nameAsImport = astBuilder.imports.firstOrNull { it.name == name }?.path
                if (nameAsImport != null) {
                    ImportedExpression(nameAsImport, scope, origin)
                } else {
                    // try to find the field:
                    //  check super scopes until a class appears,
                    //  then check hierarchy scopes
                    //  -> inner classes (anonymous inline classes) have access to multiple scopes
                    //  -> for the whole hierarchy, try to find fields and parent classes
                    // when this is executed, the field might not yet be known
                    //  -> and we must respect the hierarchy -> we can only execute this later on
                    NameExpression(name, scope, origin)
                }
            }
        }
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = name
    override fun clone(scope: Scope) = NameExpression(name, scope, origin)
    override fun hasLambdaOrUnknownGenericsType(): Boolean = false
    override fun resolveType(context: ResolutionContext): Type {
        val field = resolveField(context, name, null)
        if (field != null) return field.getValueType()

        val type = findType(context.codeScope, context.selfType, name)
        if (type != null) return type

        throw IllegalStateException(
            "Missing field/type '${name}' in ${context.selfType}, ${context.codeScope}, " +
                    resolveOrigin(origin)
        )
    }
}