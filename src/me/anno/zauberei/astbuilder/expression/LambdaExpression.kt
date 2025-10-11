package me.anno.zauberei.astbuilder.expression

class LambdaExpression(
    val variables: List<LambdaVariable>,
    val body: Expression,
) : Expression(body.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(body)
    }

    override fun toString(): String {
        return "Lambda[$variables]{ $body }"
    }
}