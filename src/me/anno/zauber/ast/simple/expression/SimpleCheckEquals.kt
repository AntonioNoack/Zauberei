package me.anno.zauber.ast.simple.expression

import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.ReturnType
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.members.ResolvedMethod

class SimpleCheckEquals(
    dst: SimpleField,
    val left: SimpleField, val right: SimpleField,
    val negated: Boolean,
    val method: ResolvedMethod,
    scope: Scope, origin: Long
) : SimpleCallable(
    dst, left, method.specialization,
    listOf(right), scope, origin
) {

    override fun toString(): String {
        return "$dst = $left ${if (negated) "!=" else "=="} $right"
    }

    override fun eval(): BlockReturn {
        val runtime = runtime
        val va = runtime[left]
        val vb = runtime[right]
        if (va == vb) return BlockReturn(ReturnType.VALUE, runtime.getBool(!negated))

        val vaNull = runtime.isNull(va)
        val vbNull = runtime.isNull(vb)
        if (vaNull != vbNull) return BlockReturn(ReturnType.VALUE, runtime.getBool(negated))

        // todo handle yield from this...
        // todo this can involve dynamic dispatch
        val method1 = method.specialization
        val result = runtime.executeCall(
            va, null,
            method1, listOf(right), origin
        )

        if (!negated) return result.retToVal()

        return when (result.type) {
            ReturnType.THROW -> result
            ReturnType.RETURN, ReturnType.VALUE -> {
                val value = result.value.castToBool()
                BlockReturn(ReturnType.VALUE, runtime.getBool(!value))
            }
            else -> throw NotImplementedError("$result in SimpleCheckEquals")
        }
    }

    override fun hasInput(field: SimpleField): Boolean {
        return left == field || right == field
    }

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleCheckEquals(
            src.cloned(this.dst, dst),
            src.cloned(left, dst),
            src.cloned(right, dst),
            negated, method, scope, origin
        )
    }

}