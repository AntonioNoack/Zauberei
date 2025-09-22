package me.anno.zauberei.astbuilder.expression

class LambdaExpression(
    val names: List<LambdaVariable>,
    val members: List<Expression>
) : Expression() {
    open class LambdaVariable(val name: String)
    class LambdaDestructuring(val names: List<String>) : LambdaVariable("")
}