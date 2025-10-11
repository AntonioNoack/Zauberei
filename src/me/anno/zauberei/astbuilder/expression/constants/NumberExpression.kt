package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Types.CharType
import me.anno.zauberei.types.Types.DoubleType
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.HalfType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.LongType
import me.anno.zauberei.types.Types.UIntType
import me.anno.zauberei.types.Types.ULongType

class NumberExpression(val value: String, origin: Int) : Expression(origin) {

    init {
        // based on the string content, decide what type this is
        resolvedType = when {
            value.startsWith("'") -> CharType
            value.startsWith("0x", true) ||
                    value.startsWith("-0x", true) -> resolveIntType()
            value.endsWith('h') || value.endsWith('H') -> HalfType
            value.endsWith('f') || value.endsWith('F') -> FloatType
            value.endsWith('d') || value.endsWith('D') -> DoubleType
            // todo does Kotlin have numbers with binary exponent?
            value.contains('.') || value.contains('e') || value.contains('E') -> DoubleType
            else -> resolveIntType()
        }
    }

    private fun resolveIntType(): ClassType {
        return when {
            value.endsWith("ul", true) -> ULongType
            value.endsWith("u", true) -> UIntType
            value.endsWith("l", true) -> LongType
            // todo depending on the length, value may become long, too
            else -> IntType
        }
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "#$value"
    }
}