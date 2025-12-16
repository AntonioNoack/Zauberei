package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope

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
}