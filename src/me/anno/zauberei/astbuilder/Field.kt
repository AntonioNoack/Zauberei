package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

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

    init {
        if (name == "valueParameters") {
            println("Created field $this with initial $valueType, $initialValue")
        }
    }

    var privateGet = false
    var privateSet = false

    var getterExpr: Expression? = null

    var setterFieldName: String = "value"
    var setterExpr: Expression? = null

    init {
        declaredScope.addField(this)
    }

    val specificTypes = ArrayList<ScopedFieldType>()

    override fun toString(): String {
        return "Field($selfType.$name=$initialValue)"
    }
}