package me.anno.zauberei.astbuilder.expression

class BreakExpression(val label: String?) : Expression() {
    override fun toString(): String {
        return if (label != null) "break@$label" else "break"
    }
}