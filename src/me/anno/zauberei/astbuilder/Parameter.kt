package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.graph.KnownType
import me.anno.zauberei.types.Type

class Parameter(
    val isVar: Boolean,
    val isVal: Boolean,
    val isVararg: Boolean,
    val name: String,
    val type: Type,
    val initialValue: Expression?
) {

    lateinit var typeI: KnownType

    override fun toString(): String {
        return "${if (isVar) "var " else ""}${if (isVal) "val " else ""}$name: $type${if(initialValue != null) " = $initialValue" else ""}"
    }
}