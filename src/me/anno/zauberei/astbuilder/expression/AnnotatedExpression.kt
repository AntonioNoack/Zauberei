package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.Annotation

class AnnotatedExpression(val annotation: Annotation, val base: Expression) : Expression() {
    override fun toString(): String {
        return "$annotation$base"
    }
}