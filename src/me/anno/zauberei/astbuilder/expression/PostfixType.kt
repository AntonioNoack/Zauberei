package me.anno.zauberei.astbuilder.expression

enum class PostfixType(val symbol: String) {
    INCREMENT("++"),
    DECREMENT("--"),
    ENSURE_NOT_NULL("!!")
}
