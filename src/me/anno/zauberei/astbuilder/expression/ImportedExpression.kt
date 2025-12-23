package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ImportedExpression(
    val nameAsImport: Scope,
    scope: Scope, origin: Int
) : Expression(scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = nameAsImport.name

    override fun clone() = ImportedExpression(nameAsImport, scope, origin)

    override fun hasLambdaOrUnknownGenericsType(): Boolean = false

    override fun resolveType(context: ResolutionContext): Type {
        val imported = nameAsImport
        val typeParams = if (imported.scopeType == ScopeType.OBJECT) emptyList<Type>() else null
        return ClassType(imported, typeParams)
    }
}