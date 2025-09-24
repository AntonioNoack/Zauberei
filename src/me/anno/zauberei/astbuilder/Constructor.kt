package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression

class Constructor(
    val parameters: List<Parameter>,
    val superCall: Expression?,
    val body: Expression?,
    val keywords: List<String>
) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {

        for (param in parameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }

        if (superCall != null) callback(superCall)
        if (body != null) callback(body)
    }
}