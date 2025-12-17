package me.anno.zauberei.astbuilder.expression

enum class BinaryTypeOpType(val symbol: String) {
    CAST_OR_CRASH("as"),
    CAST_OR_NULL("as?"),
    INSTANCEOF("is"),
}