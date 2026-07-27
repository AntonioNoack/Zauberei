package me.anno.zauber.ast.simple.controlflow

import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.ReturnType
import me.anno.zauber.scope.Scope

class SimpleThrow(field: SimpleField, scope: Scope, origin: Long) : SimpleExit(field, scope, origin) {
    override val returnType: ReturnType get() = ReturnType.THROW
    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleThrow(
            src.cloned(value, dst),
            scope, origin
        )
    }
}