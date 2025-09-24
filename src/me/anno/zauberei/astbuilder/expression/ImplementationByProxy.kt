package me.anno.zauberei.astbuilder.expression

class ImplementationByProxy(val clazz: Expression, val implementation: Expression): Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(clazz)
        callback(implementation)
    }
}