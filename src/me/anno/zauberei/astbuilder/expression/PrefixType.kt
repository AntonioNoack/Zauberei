package me.anno.zauberei.astbuilder.expression

enum class PrefixType(val symbol: String) {
    NOT("!"),
    MINUS("-"),
    INCREMENT("++"),
    DECREMENT("--"),
    ARRAY_TO_VARARGS("*")
}