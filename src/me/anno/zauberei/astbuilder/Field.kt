package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.typeresolution.members.ResolvedMethod.Companion.selfTypeToTypeParams
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

    val selfTypeTypeParams: List<Parameter>
        get() = selfTypeToTypeParams(selfType)

    var typeParameters: List<Parameter> = emptyList()

    init {
        declaredScope.addField(this)
    }

    val specificTypes = ArrayList<ScopedFieldType>()

    fun deductValueType(context: ResolutionContext): Type {
        val valueType = valueType
        if (valueType != null) return valueType

        val value = initialValue
            ?: getterExpr
            ?: throw IllegalStateException("Field $this has neither type, nor initial/getter")
        return TypeResolution.resolveType(context, value)
    }

    override fun toString(): String {
        return "Field($selfType.$name=$initialValue)"
    }
}