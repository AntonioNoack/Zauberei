package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression

class Constructor(
    val typeParameters: List<Parameter>,
    val parameters: List<Parameter>,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>
) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {

        for (typeParam in typeParameters) {
            if (typeParam.initialValue != null)
                callback(typeParam.initialValue)
        }

        for (param in parameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }

        if (superCall != null) callback(superCall)
        if (body != null) callback(body)
    }
}