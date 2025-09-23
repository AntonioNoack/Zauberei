package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.expression.Expression

class Field(
    val isVar: Boolean,
    val isVal: Boolean,
    val ownerType: Type,
    val name: String,
    val valueType: Type?,
    val initialValue: Expression?,
    val keywords: List<String>
) {

    var privateGet = false
    var privateSet = false

    var getterExpr: Expression? = null

    var setterFieldName: String = "value"
    var setterExpr: Expression? = null

}