package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhenBranchExpression(val cases: List<WhenCase>) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (case in cases) {
            if (case.condition != null) callback(case.condition)
            callback(case.body)
        }
    }

    override fun toString(): String {
        return "when { ${cases.joinToString("; ")} }"
    }
}