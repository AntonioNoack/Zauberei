package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.getScope
import me.anno.zauberei.types.impl.ClassType

class GetClassFromTypeExpression(val base: Scope, origin: Int) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {}

    override fun toString(): String {
        return "$base::class"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return ClassType(getScope("Class"), listOf(ClassType(base, null)))
    }
}