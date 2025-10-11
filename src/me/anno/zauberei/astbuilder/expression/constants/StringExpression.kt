package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Types.StringType

class StringExpression(val value: String, origin: Int): Expression(origin) {

    init {
        resolvedType = StringType
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = "\"$value\""
}