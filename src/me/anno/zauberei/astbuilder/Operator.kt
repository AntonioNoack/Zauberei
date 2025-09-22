package me.anno.zauberei.astbuilder

enum class Assoc { LEFT, RIGHT }

data class Operator(val symbol: String, val precedence: Int, val assoc: Assoc)

private val operators = mapOf(
    "=" to Operator("=", 5, Assoc.RIGHT),
    "||" to Operator("||", 6, Assoc.LEFT),
    "&&" to Operator("&&", 7, Assoc.LEFT),
    "==" to Operator("==", 8, Assoc.LEFT),
    "!=" to Operator("!=", 8, Assoc.LEFT),
    "<" to Operator("<", 9, Assoc.LEFT),
    ">" to Operator(">", 9, Assoc.LEFT),
    "<=" to Operator("<=", 9, Assoc.LEFT),
    ">=" to Operator(">=", 9, Assoc.LEFT),
    "+" to Operator("+", 10, Assoc.LEFT),
    "-" to Operator("-", 10, Assoc.LEFT),
    "*" to Operator("*", 20, Assoc.LEFT),
    "/" to Operator("/", 20, Assoc.LEFT),
    "%" to Operator("%", 20, Assoc.LEFT)
    // add more as needed
)