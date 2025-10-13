package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList

class WhenCase(val condition: Expression?, val body: Expression) {
    override fun toString(): String {
        return "${condition ?: "else"} -> { $body }"
    }
}

@Suppress("FunctionName")
fun WhenBranchExpression(cases: List<WhenCase>, origin: Int): Expression {
    var chain: Expression? = null
    for (i in cases.indices.reversed()) {
        val caseI = cases[i]
        chain = if (caseI.condition != null) {
            IfElseBranch(caseI.condition, caseI.body, chain)
        } else {
            if (chain != null) throw IllegalStateException("Else must be the last case")
            caseI.body
        }
    }
    return chain ?: ExpressionList(emptyList(), origin)
}