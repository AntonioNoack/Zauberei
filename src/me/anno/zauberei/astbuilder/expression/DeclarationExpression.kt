package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

class DeclarationExpression(
    val name: String, val type: Type?, val initialValue: Expression?,
    val isVar: Boolean, val isLateinit: Boolean
) : Expression() {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        if (initialValue != null) callback(initialValue)
    }

    override fun toString(): String {
        return (if (isVar) if (isLateinit) "lateinit var" else "var " else "val ") +
                "$name: $type${if (initialValue != null) " = $initialValue" else ""}"

    }
}