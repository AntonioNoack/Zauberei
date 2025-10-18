package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.types.Type

class CallExpression(
    val base: Expression, val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    origin: Int
) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in valueParameters.indices) {
            callback(valueParameters[i].value)
        }
    }

    override fun toString(): String {
        return if (typeParameters == null) {
            "($base)($valueParameters)"
        } else {
            "($base)<${typeParameters.joinToString()}>($valueParameters)"
        }
    }
}