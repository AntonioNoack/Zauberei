package me.anno.zauberei.astbuilder.expression

class IntCompareZeroOp(val value: Expression, val type: CompareType) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(value)
    }

    override fun toString(): String {
        return "($value) $type 0"
    }
}