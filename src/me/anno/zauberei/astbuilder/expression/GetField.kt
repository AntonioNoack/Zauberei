package me.anno.zauberei.astbuilder.expression

// todo assignments, just like in Kotlin, always directly write to the member "field"
//  and other assignment will call a getter/setter function instead
class GetField: Expression() {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
}