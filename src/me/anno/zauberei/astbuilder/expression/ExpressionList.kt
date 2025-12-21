package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

class ExpressionList(val list: List<Expression>, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (i in list.indices) {
            callback(list[i])
        }
    }

    override fun toString(): String {
        return "[${list.joinToString("; ")}]"
    }

    override fun resolveType(context: ResolutionContext): Type {
        val lastExpr = list.lastOrNull()
        return if (lastExpr != null) {
            TypeResolution.resolveType(context, lastExpr)
        } else if (context.allowTypeless) {
            UnitType
        } else {
            throw IllegalStateException("Expected some type, but got empty expression list")
        }
    }
}