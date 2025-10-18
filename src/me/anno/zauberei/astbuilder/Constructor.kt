package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope

class Constructor(
    val typeParameters: List<Parameter>,
    val valueParameters: List<Parameter>,
    val parameterScope: Scope,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>,
    origin: Int
) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {

        for (param in valueParameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }

        if (superCall != null) callback(superCall)
        if (body != null) callback(body)
    }
}