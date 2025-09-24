package me.anno.zauberei.astbuilder.expression

// todo assignments, just like in Kotlin, always directly write to the member "field"
//  and other assignment will call a getter/setter function instead
class SetField(val expression: Expression) : Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(expression)
    }
}