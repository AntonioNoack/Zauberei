package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

class WhileLoop(val condition: Expression, val body: Expression, val label: String?) :
    Expression(condition.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(condition)
        callback(body)
    }

    override fun toString(): String {
        return "${if (label != null) "$label@" else ""} while($condition) { $body }"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return exprHasNoType(context)
    }
}