package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

abstract class Expression(val origin: Int) {

    /**
     * cached for faster future resolution and for checking in from later stages
     * */
    var resolvedType: Type? = null

    abstract fun forEachExpr(callback: (Expression) -> Unit)
    abstract fun resolveType(context: ResolutionContext): Type

    fun exprHasNoType(context: ResolutionContext): Type {
        if (!context.allowTypeless) throw IllegalStateException("Expected type, but found $this in ${resolveOrigin(origin)}")
        return UnitType
    }

    init {
        numExpressionsCreated++
    }

    companion object {
        var numExpressionsCreated = 0
            private set
    }
}