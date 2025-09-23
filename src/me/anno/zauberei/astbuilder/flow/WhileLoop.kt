package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class WhileLoop(val condition: Expression, val body: Expression, val label: String?) : Expression() {
    override fun toString(): String {
        return "${if(label != null) "$label@" else ""} while($condition) { $body }"
    }
}