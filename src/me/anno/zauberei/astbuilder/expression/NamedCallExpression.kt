package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.types.Type

class NamedCallExpression(
    val base: Expression,
    val name: String,
    val typeParams: List<Type>?,
    val params: List<NamedParameter>,
    origin: Int
) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in params.indices) {
            callback(params[i].value)
        }
    }

    override fun toString(): String {
        return if (typeParams == null) {
            "($base).$name($params)"
        } else {
            "($base).$name<${typeParams.joinToString()}>($params)"
        }
    }
}