package me.anno.zauber.ast.reverse

import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleAssignment
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope

class SimpleLocalFieldEqualsInt(
    dst: SimpleField,
    val field: LocalField, val value: Int,
    scope: Scope, origin: Long
) : SimpleAssignment(dst, scope, origin) {

    override fun execute(): BlockReturn? {
        // fast-path
        val runtime = runtime
        val actual = runtime.getCall().localFields[field.id]
            ?: error("Missing value for $field")
        runtime[dst] = runtime.getBool(actual.castToInt() == value)
        return null
    }

    override fun toString(): String {
        return "$dst = $field == ${style("$value", StringStyles.DARK_BLUE)}"
    }

    override fun hasInput(field: SimpleField): Boolean = false

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleLocalFieldEqualsInt(
            src.cloned(this.dst, dst),
            src.cloned(field, dst), value,
            scope, origin
        )
    }

}