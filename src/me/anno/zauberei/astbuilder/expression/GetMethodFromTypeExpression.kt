package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class GetMethodFromTypeExpression(val base: Scope, val name: String, scope: Scope, origin: Int) :
    Expression(scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "$base::$name"
    }

    override fun resolveType(context: ResolutionContext): Type {
        // todo resolve method, then convert signature into lambda
        TODO("Not yet implemented")
    }

    override fun clone(scope: Scope) = GetMethodFromTypeExpression(base, name, scope, origin)

}