package me.anno.zauber.ast.simple.expression

import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope

class SimpleCheckIdentical(
    dst: SimpleField, val left: SimpleField, val right: SimpleField,
    val negated: Boolean,
    scope: Scope, origin: Long
) : SimpleAssignment(dst, scope, origin) {

    init {
        val leftIsNative = left.type.isValueOrNative()
        val rightIsNative = right.type.isValueOrNative()
        if (leftIsNative || rightIsNative) {
            error("Should not check values for identical, $this")
        }
    }

    override fun toString(): String {
        return "$dst = $left ${if (negated) "!==" else "==="} $right"
    }

    override fun execute(): BlockReturn? {
        val runtime = runtime
        val left = runtime[left]
        val right = runtime[right]
        runtime[dst] = runtime.getBool((left == right) xor negated)
        return null
    }

    override fun hasInput(field: SimpleField): Boolean {
        return field == left || field == right
    }

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleCheckIdentical(
            src.cloned(this.dst, dst),
            src.cloned(left, dst),
            src.cloned(right, dst),
            negated, scope, origin
        )
    }

}