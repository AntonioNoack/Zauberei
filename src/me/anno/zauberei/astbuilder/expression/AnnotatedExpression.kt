package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.Annotation
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

class AnnotatedExpression(val annotation: Annotation, val base: Expression) : Expression(base.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "$annotation$base"
    }

    override fun resolveType(context: ResolutionContext): Type {
        return base.resolveType(context)
    }

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return base.hasLambdaOrUnknownGenericsType()
    }

    override fun clone() = AnnotatedExpression(annotation, base.clone())
}