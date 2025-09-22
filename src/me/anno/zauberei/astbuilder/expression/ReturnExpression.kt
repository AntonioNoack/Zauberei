package me.anno.zauberei.astbuilder.expression

class ReturnExpression(val base: Expression?, val label: String?) : Expression() {
    override fun toString(): String {
        return "return $base"
    }
}