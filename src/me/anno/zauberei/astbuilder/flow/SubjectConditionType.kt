package me.anno.zauberei.astbuilder.flow

enum class SubjectConditionType(val symbol: String) {
    INSTANCEOF("is"),
    NOT_INSTANCEOF("!is"),
    CONTAINS("in"),
    NOT_CONTAINS("!in"),
    EQUALS("=="),
}