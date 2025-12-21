package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

class AssignmentExpression(var variableName: Expression, var newValue: Expression) : Expression(newValue.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(variableName)
        callback(newValue)
    }

    override fun toString(): String {
        return "$variableName=$newValue"
    }

    override fun resolveType(context: ResolutionContext): Type {
        if (!context.allowTypeless) throw IllegalStateException("Cannot resolve assignment to type")
        return UnitType
    }
}