package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhenBranchExpression(val cases: List<WhenCase>) : Expression() {
    override fun toString(): String {
        return "when { ${cases.joinToString("; ")} }"
    }
}