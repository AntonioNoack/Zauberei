package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class Method(
    val selfType: Type?,
    var name: String?,
    val typeParameters: List<Parameter>,
    val valueParameters: List<Parameter>,
    // todo defined constructors need this extra scope, too
    val innerScope: Scope,
    var returnType: Type?,
    val extraConditions: List<TypeCondition>,
    val body: Expression?,
    val keywords: List<String>,
    val origin: Int
) {
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("fun ")
        if (typeParameters.isNotEmpty()) {
            builder.append('<')
            builder.append(typeParameters.joinToString(", ") {
                "${it.name}: ${it.type}"
            })
            builder.append("> ")
        }
        if (selfType != null) {
            builder.append(selfType.toString()).append('.')
        }
        builder.append(name)
        builder.append('(')
        builder.append(valueParameters.joinToString(", "))
        builder.append(')')
        return builder.toString()
    }
}