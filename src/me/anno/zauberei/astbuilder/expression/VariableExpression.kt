package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.findField
import me.anno.zauberei.typeresolution.TypeResolution.findType
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveFieldType
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class VariableExpression(val name: String, var owner: Scope?, var field: Field?, origin: Int) : Expression(origin) {

    /**
     * for constructors, so we don't necessarily need to store imports in a scope
     * (because scopes can belong to multiple files, but imports always belong to a specific file)
     * */
    var nameAsImport: Scope? = null

    constructor(name: String, origin: Int, astBuilder: ASTBuilder) : this(name, null, null, origin) {
        nameAsImport = astBuilder.imports.firstOrNull { it.name == name }?.path
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = name

    override fun clone() = VariableExpression(name, owner, field, origin)

    override fun resolveType(context: ResolutionContext): Type {
        val field = findField(context.codeScope, context.selfScope?.typeWithoutArgs, name)
            ?: findField(langScope, context.selfScope?.typeWithoutArgs, name)
        if (field != null) return resolveFieldType(field)
        val type = findType(context.codeScope, context.selfType, name)
        if (type != null) return type
        throw IllegalStateException(
            "Missing field '${name}' in ${context.codeScope},${context.selfType}, " +
                    resolveOrigin(origin)
        )
    }
}