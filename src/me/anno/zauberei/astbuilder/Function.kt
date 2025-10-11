package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Type

class Function(
    val selfType: Type?,
    var name: String?,
    val typeParameters: List<Parameter>,
    val parameters: List<Parameter>,
    val returnType: Type?,
    val extraConditions: List<TypeCondition>,
    val body: Expression?,
    val keywords: List<String>
) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (param in typeParameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }
        for (param in parameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }
        if (body != null) callback(body)
    }
}