package me.anno.zauber.types.impl

import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type

class TypeOfExpr(val expr: Expression) : Type() {

    override fun isResolved(): Boolean = false

    private val resolved by lazy {
        expr.resolveValueType(
            ResolutionContext(
                expr.scope, null, Specialization.noSpecialization,
                false, null, emptyMap(), emptyList()
            )
        )
    }

    override fun resolveImpl(selfScope: Scope?): Type = resolved

    override fun toStringImpl(depth: Int): String {
        return "typeOf($expr)"
    }
}