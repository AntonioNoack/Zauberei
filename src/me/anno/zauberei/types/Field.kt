package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.complex.ResolvingType

class Field(
    val declaredScope: Scope,
    val isVar: Boolean,
    val isVal: Boolean,
    val selfType: Type?, // may be null inside methods, owner is stack, kind of
    val name: String,
    var valueType: Type?,
    val initialValue: Expression?,
    val keywords: List<String>,
    val origin: Int
) {

    lateinit var valueType1: ResolvingType

    var privateGet = false
    var privateSet = false

    var getterExpr: Expression? = null

    var setterFieldName: String = "value"
    var setterExpr: Expression? = null

    init {
        declaredScope.addField(this)
    }

}