package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

class TryCatchBlock(val tryBody: Expression, val catches: List<Catch>, val finallyExpression: Expression?) :
    Expression(tryBody.scope, tryBody.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(tryBody)
        for (catch in catches) {
            callback(catch.handler)
        }
        if (finallyExpression != null)
            callback(finallyExpression)
    }

    override fun resolveType(context: ResolutionContext): Type {
        val bodyType = TypeResolution.resolveType(context, tryBody)
        val catchTypes = catches.map {
            TypeResolution.resolveType(context, it.handler)
        }.reduceOrNull { a, b -> unionTypes(a, b) }
        return if (catchTypes == null) bodyType
        else unionTypes(bodyType, catchTypes)
    }

    override fun clone() = TryCatchBlock(tryBody.clone(), catches.map {
        Catch(it.param.clone(), it.handler.clone())
    }, finallyExpression?.clone())

}