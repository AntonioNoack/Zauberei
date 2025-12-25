package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.NothingType

// todo we maybe can pack this into an return Err(thrown), and return into return Ok(value)
class ThrowExpression(origin: Int, val thrown: Expression) : Expression(thrown.scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(thrown)
    }

    override fun toString(): String {
        return "throw $thrown"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return NothingType
    }

    override fun clone(scope: Scope) = ThrowExpression(origin, thrown.clone(scope))

}