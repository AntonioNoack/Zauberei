package me.anno.zauberei.tokenizer

enum class TokenType {

    NAME, // starts with A-Za-z_
    STRING,
    NUMBER, // starts with 0-9.; a char is a special number
    SYMBOL, // anything like +-*/=&%$§
    LABEL,

    KEYWORD,

    APPEND_STRING,// special string concat operator

    // todo the following could all be symbols
    COMMA,

    OPEN_CALL,
    OPEN_BLOCK,
    OPEN_ARRAY,

    CLOSE_CALL,
    CLOSE_BLOCK,
    CLOSE_ARRAY,

    BLOCK_COMMENT

    ;
}