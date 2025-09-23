package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Type

class BinaryTypeOp(val left: Expression, val symbol: String, val right: Type) : Expression() {
    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}