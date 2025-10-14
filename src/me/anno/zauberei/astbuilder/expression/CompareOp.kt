package me.anno.zauberei.astbuilder.expression

enum class CompareType(val symbol: String) {
    LESS("<"),
    GREATER(">"),
    LESS_EQUALS("<="),
    GREATER_EQUALS(">="),
}

class CompareOp(val value: Expression, val type: CompareType) : Expression(value.origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(value)
    }

    override fun toString(): String {
        return "($value) ${type.symbol} 0"
    }
}