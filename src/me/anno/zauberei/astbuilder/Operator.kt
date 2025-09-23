package me.anno.zauberei.astbuilder

enum class Assoc { LEFT, RIGHT }

data class Operator(val symbol: String, val precedence: Int, val assoc: Assoc)

val operators = mapOf(

    // assignments
    "=" to Operator("=", 1, Assoc.RIGHT),
    "+=" to Operator("+=", 1, Assoc.RIGHT),
    "-=" to Operator("-=", 1, Assoc.RIGHT),
    "*=" to Operator("*=", 1, Assoc.RIGHT),
    "/=" to Operator("/=", 1, Assoc.RIGHT),
    "%=" to Operator("%=", 1, Assoc.RIGHT),

    "?:" to Operator("?:", 2, Assoc.LEFT),

    // infix
    "shl" to Operator("shl", 3, Assoc.LEFT),
    "shr" to Operator("shr", 3, Assoc.LEFT),
    "ushr" to Operator("ushr", 3, Assoc.LEFT),
    "and" to Operator("and", 3, Assoc.LEFT),
    "or" to Operator("or", 3, Assoc.LEFT),
    "xor" to Operator("xor", 3, Assoc.LEFT),
    "in" to Operator("in", 3, Assoc.LEFT),
    "is" to Operator("is", 3, Assoc.LEFT),
    "as" to Operator("as", 3, Assoc.LEFT),
    "as?" to Operator("as?", 3, Assoc.LEFT),
    "!in" to Operator("!in", 3, Assoc.LEFT),
    "!is" to Operator("!is", 3, Assoc.LEFT),
    "to" to Operator("to", 3, Assoc.LEFT),
    "step" to Operator("step", 3, Assoc.LEFT),
    "until" to Operator("until", 3, Assoc.LEFT),
    "downTo" to Operator("downTo", 3, Assoc.LEFT),
    ".." to Operator("..", 3, Assoc.LEFT),

    // logical
    "||" to Operator("||", 6, Assoc.LEFT),
    "&&" to Operator("&&", 7, Assoc.LEFT),

    // comparing
    "===" to Operator("===", 8, Assoc.LEFT),
    "!==" to Operator("!==", 8, Assoc.LEFT),
    "==" to Operator("==", 8, Assoc.LEFT),
    "!=" to Operator("!=", 8, Assoc.LEFT),
    "<" to Operator("<", 9, Assoc.LEFT),
    ">" to Operator(">", 9, Assoc.LEFT),
    "<=" to Operator("<=", 9, Assoc.LEFT),
    ">=" to Operator(">=", 9, Assoc.LEFT),

    // maths
    "+" to Operator("+", 10, Assoc.LEFT),
    "-" to Operator("-", 10, Assoc.LEFT),
    "*" to Operator("*", 20, Assoc.LEFT),
    "/" to Operator("/", 20, Assoc.LEFT),
    "%" to Operator("%", 20, Assoc.LEFT),

    "?." to Operator(".?", 30, Assoc.LEFT),
    "." to Operator(".", 30, Assoc.LEFT),
    "::" to Operator("::", 30, Assoc.LEFT),
    // add more as needed
)