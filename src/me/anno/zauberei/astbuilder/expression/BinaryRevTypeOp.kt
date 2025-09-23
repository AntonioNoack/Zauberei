package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.types.Package

class BinaryRevTypeOp(val left: Package, val symbol: String, val right: Expression) : Expression() {
    override fun toString(): String {
        return "($left)$symbol($right)"
    }
}