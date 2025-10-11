package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolvingType

class Field(
    val isVar: Boolean,
    val isVal: Boolean,
    val ownerType: Type,
    val name: String,
    var valueType: Type?,
    val initialValue: Expression?,
    val keywords: List<String>
) {

    lateinit var valueType1: ResolvingType

    var privateGet = false
    var privateSet = false

    var getterExpr: Expression? = null

    var setterFieldName: String = "value"
    var setterExpr: Expression? = null

}