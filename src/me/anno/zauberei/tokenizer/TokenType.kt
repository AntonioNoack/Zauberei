package me.anno.zauberei.tokenizer

enum class TokenType {

    STRING, // starts with A-Za-z_
    NUMBER, // starts with 0-9.
    SYMBOL,

    COMMA,
    SEMICOLON,

    OPEN_CALL,
    OPEN_BLOCK,
    OPEN_ARRAY,

    CLOSE_CALL,
    CLOSE_BLOCK,
    CLOSE_ARRAY

}