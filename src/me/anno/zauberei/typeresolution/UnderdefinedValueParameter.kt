package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.types.Type

/**
 * A value parameter that depends on what it's used for.
 * */
class UnderdefinedValueParameter(
    val param: NamedParameter,
    val context: ResolutionContext,
) : ValueParameter(param.name) {

    override fun getType(targetType: Type): Type {
        val expr = param.value
        println("Expr for resolving lambda/generics: $expr, tt: $targetType")
        // this is targetType-specific, so we should clone expr
        return resolveType(context.withTargetType(targetType), expr.clone(expr.scope))
    }

    override fun toString(): String {
        return "UnderdefinedValueParameter(name=$name,context=$context)"
    }
}