package me.anno.zauberei.tokenizer

enum class TokenType {

    NAME, // starts with A-Za-z_
    STRING,
    NUMBER, // starts with 0-9.; a char is a special number
    SYMBOL, // anything like +-*/=&%$§

    COMMA,

    OPEN_CALL,
    OPEN_BLOCK,
    OPEN_ARRAY,

    CLOSE_CALL,
    CLOSE_BLOCK,
    CLOSE_ARRAY,

    PLUS // special string concat operator
    ;

    val contentAlwaysSame: Boolean
        get() = when (this) {
            OPEN_CALL, OPEN_BLOCK, OPEN_ARRAY,
            CLOSE_CALL, CLOSE_BLOCK, CLOSE_ARRAY -> true
            else -> false
        }

}