package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.types.Type

class NamedCallExpression(
    val base: Expression,
    val name: String,
    val typeParameters: List<Type>?,
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
        return if (
            typeParameters.isNullOrEmpty() && name == "." &&
            valueParameters.size == 1 &&
            valueParameters[0].value is VariableExpression
        ) {
            if (base is VariableExpression) {
                "$base.${valueParameters[0].value}"
            } else {
                "($base).${valueParameters[0].value}"
            }
        } else {
            "($base).$name<${typeParameters ?: "?"}>($valueParameters)"
        }
    }
}