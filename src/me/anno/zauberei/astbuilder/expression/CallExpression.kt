package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.types.Type

/**
 * Calls base<typeParams>(valueParams)
 * */
class CallExpression(
    val base: Expression,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    origin: Int
) : Expression(origin) {

    init {
        println("Created CallExpression($base, $typeParameters, $valueParameters)")
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in valueParameters.indices) {
            callback(valueParameters[i].value)
        }
    }

    override fun toString(): String {
        return if (typeParameters != null && typeParameters.isEmpty()) {
            "($base)($valueParameters)"
        } else "($base)<${typeParameters ?: "?"}>($valueParameters)"
    }
}