package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.getScope
import me.anno.zauberei.types.impl.ClassType

class GetClassFromValueExpression(val type: Expression, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(type)
    }

    override fun toString(): String {
        return "($type)::class"
    }

    override fun resolveType(context: ResolutionContext): Type {
        val base = TypeResolution.resolveType(context, type)
        return ClassType(getScope("Class"), listOf(base))
    }

    override fun clone() = GetClassFromValueExpression(type.clone(), origin)

}