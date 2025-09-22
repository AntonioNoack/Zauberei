package me.anno.zauberei.astbuilder.expression

class ContinueExpression(val label: String?) : Expression() {
    override fun toString(): String {
        return if (label != null) "continue@$label" else "continue"
    }
}