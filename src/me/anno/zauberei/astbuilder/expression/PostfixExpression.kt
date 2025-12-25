package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.typeresolution.TypeResolution.removeNullFromType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class PostfixExpression(val base: Expression, val type: PostfixType, origin: Int) : Expression(base.scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$base${type.symbol}"
    }

    override fun clone(scope: Scope) = PostfixExpression(base.clone(scope), type, origin)

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return base.hasLambdaOrUnknownGenericsType()
    }

    override fun resolveType(context: ResolutionContext): Type {
        return when (type) {
            PostfixType.INCREMENT, PostfixType.DECREMENT -> {
                TypeResolution.resolveType(context, base)
            }
            PostfixType.ENSURE_NOT_NULL -> {
                val newTargetType = if (context.targetType != null) removeNullFromType(context.targetType) else null
                val type = TypeResolution.resolveType(context.withTargetType(newTargetType), base)
                removeNullFromType(type)
            }
        }
    }
}