package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Package
import me.anno.zauberei.types.Type

class ConstructorExpression2(
    val clazz: Package,
    val typeParams: List<Type>,
    val params: List<Expression>
) : Expression() {

    override fun toString(): String {
        return "new($clazz)($params)"
    }
}