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
            is LambdaExpression -> {
                // todo how/where can we update R to be an Int???
                println("expr for resolving lambda: $expr, tt: $targetType")
                // this is targetType-specific, so we should clone expr
                resolveType(context.withTargetType(targetType), expr.clone())
            }
            else -> TODO("Resolve $expr in ${context.codeScope}/${context.selfScope} to type $targetType")
        }
    }

    override fun toString(): String {
        return "ValueParameterWithLambda(name=$name,)"
    }
}