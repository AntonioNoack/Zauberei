package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.graph.ResolvingType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class Method(
    val selfType: Type?,
    var name: String?,
    val typeParameters: List<Parameter>,
    val valueParameters: List<Parameter>,
    // todo defined constructors need this extra scope, too
    val innerScope: Scope,
    var returnType: Type?,
    val extraConditions: List<TypeCondition>,
    val body: Expression?,
    val keywords: List<String>,
    origin: Int
) : Expression(origin) {

    lateinit var returnTypeI: ResolvingType

    override fun forEachExpr(callback: (Expression) -> Unit) {
        for (param in typeParameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }
        for (param in valueParameters) {
            if (param.initialValue != null)
                callback(param.initialValue)
        }
        if (body != null) callback(body)
    }
}