package me.anno.zauber.ast.simple.controlflow

import me.anno.utils.StringStyles
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.ReturnType
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope

abstract class SimpleExit(var value: SimpleField, scope: Scope, origin: Long) : SimpleInstruction(scope, origin) {

    abstract val returnType: ReturnType

    override fun toString(): String {
        return "${StringStyles.style(returnType.symbol, StringStyles.ORANGE)} $value"
    }

    override fun hasInput(field: SimpleField): Boolean {
        return this.value == field
    }

    override fun execute(): BlockReturn {
        val value = runtime[value]
        return BlockReturn(returnType, value)
    }
}