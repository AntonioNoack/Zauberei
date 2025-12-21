package me.anno.zauberei.astbuilder.expression

enum class PostfixType(val symbol: String) {
    INCREMENT("++"),
    DECREMENT("--"),
    ASSERT_NON_NULL("!!")
}
