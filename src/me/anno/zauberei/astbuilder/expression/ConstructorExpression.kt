package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

class ConstructorExpression(
    val className: String,
    val typeParams: List<Type>,
    val params: List<Expression>
) : Expression() {

    override fun toString(): String {
        return "new($className)($params)"
    }
}