package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList

class Field(
    val isVar: Boolean,
    val isVal: Boolean,
    val name: String,
    val type: Type?,
    val initialValue: Expression?,
    val keywords: List<String>
) {

    var privateGet = false
    var privateSet = false

    var getterExpr: Expression? = null

    var setterFieldName: String = "field"
    var setterExpr: Expression? = null

}