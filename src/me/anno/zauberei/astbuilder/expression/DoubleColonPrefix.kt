package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

/**
 * ::callMeNow -> type is some lambda
 * */
class DoubleColonPrefix(val left: Scope, val methodName: String, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "($left)::$methodName"
    }

    override fun resolveType(context: ResolutionContext): Type {
        // todo we need to resolve the method...
        TODO("Not yet implemented")
    }
}