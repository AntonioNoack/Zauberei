package me.anno.zauberei.astbuilder.expression

enum class PrefixType(val symbol: String) {
    NOT("!"),
    MINUS("-"),
    INCREMENT("++"),
    DECREMENT("--"),
    ARRAY_TO_VARARGS("*")
}

class PrefixExpression(val type: PrefixType, origin: Int, val base: Expression) : Expression(origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
    }

    override fun toString(): String {
        return "${type.symbol}$base"
    }
}