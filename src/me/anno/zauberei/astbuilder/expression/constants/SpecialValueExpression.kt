package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.resolveThisType
import me.anno.zauberei.typeresolution.TypeResolution.typeToScope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.impl.NullType

class SpecialValueExpression(val value: SpecialValue, origin: Int) : Expression(origin) {
    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String = value.name.lowercase()
    override fun resolveType(context: ResolutionContext): Type {
        return when (value) {
            SpecialValue.NULL -> NullType
            SpecialValue.TRUE, SpecialValue.FALSE -> BooleanType
            SpecialValue.THIS -> {
                // todo 'this' might have a label, and then means the parent with that name
                resolveThisType(typeToScope(context.selfType) ?: context.codeScope).typeWithoutArgs
            }
            else -> TODO("Resolve type for ConstantExpression in ${context.codeScope},${value}")
        }
    }

    override fun clone() = SpecialValueExpression(value, origin)
}