package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.LambdaExpression
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.types.Type

class ValueParameterWithLambda(
    val param: NamedParameter,
    val context: ResolutionContext,
) : ValueParameter(param.name) {

    override fun getType(targetType: Type): Type {
        return when (val expr = param.value) {
            is LambdaExpression -> resolveType(context, expr)
            else -> TODO("Resolve $expr in ${context.codeScope}/${context.selfScope} to type $targetType")
        }
    }

    override fun toString(): String {
        return "ValueParameterWithLambda(name=$name,)"
    }
}