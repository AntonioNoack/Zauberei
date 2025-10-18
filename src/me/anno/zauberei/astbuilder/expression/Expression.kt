package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.complex.ResolvingType
import me.anno.zauberei.types.Type

abstract class Expression(val origin: Int) {

    var resolvedType: Type? = null
    var resolvedTypeI: ResolvingType? = null

    abstract fun forEachExpr(callback: (Expression) -> Unit)

    init {
        counter++
    }

    companion object {
        var counter = 0
    }
}