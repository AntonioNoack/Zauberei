package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

open class LambdaVariable(val type: Type?, val name: String) {
    override fun toString(): String {
        return if (type != null) "$name: $type" else name
    }
}

class LambdaDestructuring(val names: List<String>) : LambdaVariable(null, "") {
    override fun toString(): String {
        return "(${names.joinToString(", ")})"
    }
}

class LambdaExpression(
    val variables: List<LambdaVariable>?,
    val body: Expression,
) : Expression(body.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(body)
    }

    override fun toString(): String {
        return "LambdaExpr(${variables ?: "?"} -> $body)"
    }
}