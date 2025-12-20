package me.anno.zauberei.astbuilder.expression.constants

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Types.ByteType
import me.anno.zauberei.types.Types.CharType
import me.anno.zauberei.types.Types.DoubleType
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.HalfType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.LongType
import me.anno.zauberei.types.Types.ShortType
import me.anno.zauberei.types.Types.UIntType
import me.anno.zauberei.types.Types.ULongType

class NumberExpression(val value: String, origin: Int) : Expression(origin) {

    init {
        // based on the string content, decide what type this is
        resolvedType = when {
            value.startsWith("'") -> CharType
            value.startsWith("0x", true) ||
                    value.startsWith("-0x", true) -> resolveIntType()
            value.endsWith('h', true) -> HalfType
            value.endsWith('f', true) -> FloatType
            value.endsWith('d', true) -> DoubleType
            // does Kotlin have numbers with binary exponent? -> no, but it might be useful...
            value.contains('.') || value.contains('e', true) -> DoubleType
            else -> resolveIntType()
        }
    }

    private fun resolveIntType(): ClassType {
        return when {
            value.endsWith("ul", true) -> ULongType
            value.endsWith("u", true) -> UIntType
            value.endsWith("l", true) -> LongType
           // value.length <= 3 && value.toByteOrNull() != null -> ByteType
           // value.length <= 5 && value.toShortOrNull() != null -> ShortType
            value.length <= 9 && value.toIntOrNull() != null -> IntType
            else -> LongType
        }
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {}
    override fun toString(): String {
        return "NumberExpr($value)"
    }
}