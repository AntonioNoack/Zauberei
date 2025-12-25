package me.anno.zauberei.astbuilder.controlflow

enum class SubjectConditionType(val symbol: String) {
    INSTANCEOF("is"),
    NOT_INSTANCEOF("!is"),
    CONTAINS("in"),
    NOT_CONTAINS("!in"),
    EQUALS("=="),
}