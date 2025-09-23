package me.anno.zauberei.astbuilder.expression

class LambdaExpression(
    val variables: List<LambdaVariable>,
    val members: Expression
) : Expression() {
    override fun toString(): String {
        return "Lambda[$variables]{ $members }"
    }
}