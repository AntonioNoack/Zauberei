package me.anno.zauberei.astbuilder.expression

enum class PostfixMode(val symbol: String) {
    INCREMENT("++"),
    DECREMENT("--"),
    ASSERT_NON_NULL("!!")
}
