package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType

abstract class Expression(val scope: Scope, val origin: Int) {

    /**
     * cached for faster future resolution and for checking in from later stages
     * */
    var resolvedType: Type? = null

    abstract fun forEachExpr(callback: (Expression) -> Unit)
    abstract fun resolveType(context: ResolutionContext): Type

    fun exprHasNoType(context: ResolutionContext): Type {
        if (!context.allowTypeless) throw IllegalStateException(
            "Expected type, but found $this in ${
                resolveOrigin(
                    origin
                )
            }"
        )
        return UnitType
    }

    init {
        numExpressionsCreated++
    }

    /**
     * clone to get rid of resolvedType,
     * or to change the scope
     * */
    abstract fun clone(scope: Scope): Expression

    /**
     * returns whether the type of this has a lambda, or some other unknown generics inside;
     * for lambdas, we need to know, because usually no other type information is available;
     * for unknown generics, we need them for the return type to be fully known
     * */
    open fun hasLambdaOrUnknownGenericsType(): Boolean {
        // todo what about listOf("1,2,3").map{it.split(',').map{it.toInt()}}?
        //  can we somehow hide lambdas? I don't think so...
        System.err.println("Does (${javaClass.simpleName}) $this contain a lambda? Assuming no for now...")
        return false
    }

    companion object {
        var numExpressionsCreated = 0
            private set
    }
}