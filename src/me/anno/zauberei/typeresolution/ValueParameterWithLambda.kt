package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.LambdaExpression
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ValueParameterWithLambda(
    val param: NamedParameter,
    val codeScope: Scope, // 3rd
    val selfType: Type?, // 2nd
    val selfScope: Scope?
) : ValueParameter(param.name) {

    override fun getType(targetType: Type): Type {
        when (val expr = param.value) {
            is LambdaExpression -> {
                return resolveType(
                    codeScope, selfType, selfScope, expr, false,
                    targetType
                )
            }
            else -> TODO("Resolve $expr in $codeScope/$selfScope to type $targetType")
        }
    }

    override fun toString(): String {
        return "ValueParameterWithLambda(name=$name,)"
    }
}