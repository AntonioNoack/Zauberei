package me.anno.zauberei.astbuilder.expression

import java.util.concurrent.atomic.AtomicInteger

class TmpVariableExpr(origin: Int) : Expression(origin) {

    val name = "__tmp${nextId.getAndIncrement()}"

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = name

    companion object {
        val nextId = AtomicInteger(0)
    }
}