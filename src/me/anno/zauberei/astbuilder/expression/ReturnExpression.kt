package me.anno.zauberei.astbuilder.expression

class ReturnExpression(val base: Expression?) : Expression() {
    override fun toString(): String {
        return "return $base"
    }
}