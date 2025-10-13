package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Scope

class VariableExpression(val name: String, var owner: Scope?, var field: Field?, origin: Int) : Expression(origin) {
    constructor(name: String, origin: Int) : this(name, null, null, origin)

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = name
}