package me.anno.zauberei.astbuilder.expression

class DestructuringExpression(
    val names: List<String>, val initialValue: Expression,
    val isVar: Boolean, val isLateinit: Boolean
) : Expression(initialValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(initialValue)
    }

    override fun toString(): String {
        return (if (isVar) if (isLateinit) "lateinit var" else "var " else "val ") +
                "$names${" = $initialValue"}"

    }
}