package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

abstract class Expression(val origin: Int) {

    var resolvedType: Type? = null

    abstract fun forEachExpr(callback: (Expression) -> Unit)
    abstract fun resolveType(context: ResolutionContext): Type

    fun asTypeless(context: ResolutionContext): Type {
        if (!context.allowTypeless) throw IllegalStateException("Expected type, but found $this")
        return UnitType
    }

    init {
        counter++
    }

    companion object {
        var counter = 0
    }
}