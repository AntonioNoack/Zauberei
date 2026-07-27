package me.anno.zauber.ast.simple.controlflow

import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.ReturnType
import me.anno.zauber.scope.Scope

// todo this is very special...
//  idk if we even should have a SimpleYield, or if we should "simplify" it in-place
class SimpleYield(
    field: SimpleField,
    val continueBlock: SimpleBlock,
    scope: Scope, origin: Long,
) : SimpleExit(field, scope, origin) {

    override val returnType: ReturnType get() = ReturnType.YIELD

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleYield(
            src.cloned(value, dst),
            src.cloned(continueBlock, dst),
            scope, origin
        )
    }

}