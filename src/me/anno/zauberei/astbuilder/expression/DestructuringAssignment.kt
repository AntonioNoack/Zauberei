package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

// todo generate a temporary variable, and then just assign all of them in an expression list :)
class DestructuringAssignment(
    val names: List<String>, val initialValue: Expression,
    val isVar: Boolean, val isLateinit: Boolean
) : Expression(initialValue.scope, initialValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(initialValue)
    }

    override fun toString(): String {
        return (if (isVar) if (isLateinit) "lateinit var" else "var " else "val ") +
                "(${names.joinToString()}) = $initialValue"
    }

    override fun resolveType(context: ResolutionContext): Type = exprHasNoType(context)

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false

    override fun clone(scope: Scope) = DestructuringAssignment(names, initialValue.clone(scope), isVar, isLateinit)

}