package me.anno.zauber.ast.rich.expression.constants

import me.anno.generation.java.JavaSourceGenerator.Companion.nativeJavaNumbers
import me.anno.utils.Half.Companion.toHalf
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.math.BigInteger
import kotlin.math.min
import kotlin.math.pow

// todo the "true" type of this also could be "ComptimeValue", because it is :)
class NumberExpression(val value: String, scope: Scope, origin: Long) : Expression(scope, origin) {

    companion object {

        private val LOGGER = LogManager.getLogger(NumberExpression::class)

        const val CHAR_BASE = -1

        private fun parseDigit(c: Char): Int {
            return when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'F' -> c - 'A' + 10
                in 'a'..'f' -> c - 'a' + 10
                else -> 1000
            }
        }

        fun parseFloat(value: String, basis: Int): Double {
            if (basis == CHAR_BASE) return parseChar(value).code.toDouble()
            if (value == "Infinity" || value == "+Infinity") return Double.POSITIVE_INFINITY
            if (value == "-Infinity") return Double.NEGATIVE_INFINITY
            if (value == "NaN") return Double.NaN

            var result = 0.0
            var i = 0
            if (value.startsWith('-')) i++ // skip sign
            if (value.startsWith("0x", i, true) ||
                value.startsWith("0b", i, true)
            ) {
                check(basis != 10)
                i += 2
            }
            while (i < value.length) {
                val char = value[i++]
                if (char == '_') continue
                val digit = parseDigit(char)
                if (digit >= basis) {
                    i--
                    break
                }
                result = result * basis + digit
            }
            if (i < value.length && value[i] == '.') {
                i++ // skip period
                val factor = 1.0 / basis
                var exponent = 1.0
                while (i < value.length) {
                    exponent *= factor
                    val char = value[i++]
                    if (char == '_') continue
                    val digit = parseDigit(char)
                    if (digit >= basis) {
                        i--
                        break
                    }
                    result += exponent * digit
                }
            }
            if (i + 1 < value.length && value[i] in "pP") {
                i++ // skip symbol
                val isNegative = value[i] == '-'
                if (isNegative || value[i] == '+') i++
                var exponent = 0
                while (i < value.length) {
                    // confirm with URL, that exponent should be decimal
                    // https://docs.oracle.com/javase/specs/jls/se11/html/jls-3.html#jls-BinaryExponent
                    val char = value[i++]
                    if (char == '_') continue
                    if (char in '0'..'9') {
                        exponent = exponent * 10 + (char - '0')
                        exponent = min(exponent, 1000_000) // will be null anyway
                    } else {
                        i--
                        break
                    }
                }

                if (isNegative) exponent = -exponent
                result *= 2.0.pow(exponent)
            }

            if (i + 1 < value.length && value[i] in "eE") {
                i++ // skip symbol
                val isNegative = value[i] == '-'
                if (isNegative || value[i] == '+') i++
                var exponent = 0
                while (i < value.length) {
                    // confirm with URL, that exponent should be decimal
                    // https://docs.oracle.com/javase/specs/jls/se11/html/jls-3.html#jls-BinaryExponent
                    val char = value[i++]
                    if (char == '_') continue
                    if (char in '0'..'9') {
                        exponent = exponent * 10 + (char - '0')
                        exponent = min(exponent, 1000) // will be null anyway
                    } else {
                        i--
                        break
                    }
                }

                if (isNegative) exponent = -exponent
                result *= 10.0.pow(exponent)
            }
            while (i < value.length && value[i] in "hHfFdDlLuU") i++
            check(i == value.length) {
                "Missed reading float part '${value[i]}' @$i in '$value'"
            }
            return if (value.startsWith('-')) -result else +result
        }

        fun parseInt(value: String, basis: Int, isUnsigned: Boolean): Long {
            if (basis == CHAR_BASE) return parseChar(value).code.toLong()
            if (value == "Infinity" || value == "+Infinity") return if (isUnsigned) ULong.MAX_VALUE.toLong() else Long.MAX_VALUE
            if (value == "-Infinity") return if (isUnsigned) 0L else Long.MIN_VALUE
            if (value == "NaN") return 0

            var i = 0
            val isNegative = value.startsWith('-')
            if (isNegative) i++ // skip sign
            if (value.startsWith("0x", i, true) ||
                value.startsWith("0b", i, true)
            ) {
                check(basis != 10)
                i += 2
            }

            var result = 0L
            try {
                while (i < value.length) {
                    val char = value[i++]
                    if (char == '_') continue
                    val digit = parseDigit(char)
                    if (digit >= basis) {
                        i--
                        break
                    }
                    if (isUnsigned) {
                        result = multiplyExactUnsigned(result, basis.toLong())
                        result = addExactUnsigned(result, digit.toLong())
                    } else {
                        // sum negatively, so we can parse min_value
                        result = Math.multiplyExact(result, basis.toLong())
                        result = Math.subtractExact(result, digit.toLong())
                    }
                }
            } catch (e: ArithmeticException) {
                error("${e.message} in '$value'@$i, basis $basis, ${if (isUnsigned) "unsigned" else "signed"}")
            }

            while (i < value.length && value[i] in "lLuU") i++
            check(i == value.length) {
                "Missed reading int part '${value[i]}' @$i in '$value'"
            }
            if (!isNegative && !isUnsigned && result == Long.MIN_VALUE) {
                throw ArithmeticException("long overflow")
            }
            return if (isNegative || isUnsigned) result else -result
        }

        fun parseChar(value: String): Char {
            check(value.startsWith('\''))
            var i = 1
            if (value[1] == '\\') i++
            val char = if (i == 2) when (value[i]) {
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                '0' -> '\u0000'
                'u' -> {
                    val hex = value.substring(2, 6).toInt(16)
                    i += 4 // 4 more chars
                    hex.toChar()
                }
                else -> value[i]
            } else value[i]
            check(value[i + 1] == '\'')
            check(i + 2 == value.length)
            return char
        }

        fun parseBasis(value: String): Int {
            return when {
                value.startsWith("0x", true) || value.startsWith("-0x", true) -> 16
                value.startsWith("0b", true) || value.startsWith("-0b", true) -> 2
                value.startsWith('\'') -> CHAR_BASE
                else -> 10
            }
        }

        fun Type.isFloat() = this == Types.Half || this == Types.Float || this == Types.Double
        fun Type.isSigned() = this == Types.Byte || this == Types.Short || this == Types.Int || this == Types.Long
        fun Type.isUnsigned() = this == Types.UByte || this == Types.UShort || this == Types.UInt || this == Types.ULong
        fun Type.getNumBits(): Int {
            return when (this) {
                Types.Byte, Types.UByte -> 8
                Types.Short, Types.UShort, Types.Char, Types.Half -> 16
                Types.Int, Types.UInt, Types.Float -> 32
                Types.Long, Types.ULong, Types.Double -> 64
                else -> error("Not a native number: $this")
            }
        }

        fun Type.toUnsigned(): ClassType {
            return when (this) {
                Types.Byte, Types.UByte -> Types.UByte
                Types.Short, Types.UShort, Types.Char -> Types.UShort
                Types.Int, Types.UInt -> Types.UInt
                Types.Long, Types.ULong -> Types.ULong
                else -> error("Not an integer type: $this")
            }
        }

        fun addExactUnsigned(a: Long, b: Long): Long {
            val remainingSpace = min(a.countLeadingZeroBits(), b.countLeadingZeroBits())
            if (remainingSpace <= 1) {
                // todo we could be smarter here...
                val ai = BigInteger(a.toULong().toString())
                val bi = BigInteger(b.toULong().toString())
                val result = ai + bi
                check(result.bitLength() <= 64) {
                    "Cannot multiply ${a.toULong()} by ${b.toULong()} safely"
                }
                return result.toLong() // exact version would crash
            }
            return a + b
        }

        fun multiplyExactUnsigned(a: Long, b: Long): Long {
            val usedBits = (64 - a.countLeadingZeroBits()) + (64 - b.countLeadingZeroBits())
            if (usedBits > 64) {
                val ai = BigInteger(a.toULong().toString())
                val bi = BigInteger(b.toULong().toString())
                val result = ai * bi
                check(result.bitLength() <= 64) {
                    "Cannot multiply ${a.toULong()} by ${b.toULong()} safely"
                }
                return result.toLong() // exact version would crash
            }
            return a * b
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is NumberExpression && other.value == value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    val basis = parseBasis(value)

    // based on the string content, decide what type this is
    val resolvedType0 = typeBySuffix() ?: when {
        value.startsWith("'") -> Types.Char
        basis == 16 -> resolveHexIntType()
        basis == 2 -> resolveBinIntType()
        // does Kotlin have numbers with binary exponent? -> no, but it might be useful...
        value.contains('.') ||
                value.contains('e', true) ||
                value.contains('p', true) -> Types.Double
        else -> resolveIntType()
    }

    val isFloat = resolvedType0.isFloat()
    val isChar get() = value.first() == '\''
    val isUnsigned = resolvedType0.isUnsigned()

    // todo bug: ULong would currently overflow, although it is correct and valid
    val asFloat = parseFloat(value, basis) // should always work
    val asInt = if (isFloat) asFloat.toLong() else parseInt(value, basis, isUnsigned)

    private fun resolveIntType(): ClassType {
        val extraLength = value.count { it in "_-" }
        return if (value.length <= 10 + extraLength &&
            value.replace("_", "")
                .toIntOrNull() != null
        ) Types.Int else if (value.length <= 20 + extraLength &&
            value.replace("_", "")
                .toLongOrNull() != null
        ) Types.Long else Types.ULong
    }

    private fun resolveHexIntType(): ClassType {
        val extraLength = 2 + value.count { it in "_-" }
        val start = if (value.startsWith('-')) 1 else 0
        return if (value.length <= 8 + extraLength &&
            value.removeRange(start, start + 2)
                .replace("_", "")
                .toIntOrNull(16) != null
        ) Types.Int else if (value.length <= 16 + extraLength &&
            value.replace("_", "")
                .toLongOrNull(16) != null
        ) Types.Long else Types.ULong
    }

    private fun resolveBinIntType(): ClassType {
        val extraLength = 2 + value.count { it in "_-" }
        val start = if (value.startsWith('-')) 1 else 0
        return if (value.length <= 32 + extraLength &&
            value.removeRange(start, start + 2)
                .replace("_", "")
                .toIntOrNull(2) != null
        ) Types.Int else if (value.length <= 64 + extraLength &&
            value.replace("_", "")
                .toLongOrNull(2) != null
        ) Types.Long else Types.ULong
    }

    private fun typeBySuffix(): ClassType? {
        if (value.contains('p', true)) {
            return when (value.last()) {
                'f', 'F' -> Types.Float
                'h', 'H' -> Types.Half
                else -> Types.Double
            }
        }

        val maybeIsFloat = !(value.startsWith("0x", true) || value.startsWith("-0x", true))
        return when {
            value.endsWith("ul", true) -> Types.ULong
            value.endsWith("u", true) -> Types.UInt
            value.endsWith("l", true) -> Types.Long
            value.endsWith("h", true) -> Types.Half
            maybeIsFloat && value.endsWith("f", true) -> Types.Float
            maybeIsFloat && value.endsWith("d", true) -> Types.Double
            (value.startsWith("0x", true) || value.startsWith("0b", true)) &&
                    '.' in value -> Types.Double
            else -> null
        }
    }

    override fun toStringImpl(depth: Int): String {
        return "NumberExpr($value)"
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        val actualType = resolvedType0
        val expectedType = context.targetType?.notNull() ?: return actualType
        if (expectedType == actualType) return expectedType

        if ((actualType == Types.Int || actualType == Types.Long || actualType == Types.ULong) &&
            when (expectedType) {
                Types.Long, Types.ULong -> true

                Types.Byte -> checkIntLoss { it.toInt().toByte().toLong() }
                Types.Short -> checkIntLoss { it.toInt().toShort().toLong() }

                Types.UByte -> checkIntLoss { it.toInt().toUByte().toLong() }
                Types.UShort -> checkIntLoss { it.toInt().toUShort().toLong() }
                Types.UInt -> checkIntLoss { it.toUInt().toLong() }

                Types.Half -> checkFloatLoss { it.toHalf().toDouble() }
                Types.Float -> checkFloatLoss { it.toFloat().toDouble() }
                Types.Double -> true
                else -> false
            }
        ) return expectedType

        if ((actualType == Types.Float || actualType == Types.Double) && expectedType == Types.Half) {
            checkFloatLoss { it.toHalf().toDouble() }
            return expectedType
        }

        if (actualType == Types.Double && expectedType == Types.Float) {
            checkFloatLoss { it.toFloat().toDouble() }
            return expectedType
        }

        if (expectedType in nativeJavaNumbers) {
            return expectedType
        }

        return actualType
    }

    fun checkIntLoss(cast: (Long) -> Long): Boolean {
        val maxPrecision = asInt
        val realPrecision = cast(maxPrecision)
        if (realPrecision != maxPrecision) {
            LOGGER.warn("Losing information when casting $maxPrecision to $realPrecision")
        }
        return true
    }

    fun checkFloatLoss(cast: (Double) -> Double): Boolean {
        val maxPrecision = asFloat
        val realPrecision = cast(maxPrecision)
        if (realPrecision.toString() != maxPrecision.toString()) {
            LOGGER.warn("Losing information when casting $maxPrecision to $realPrecision")
        }
        return true
    }

    override fun clone(scope: Scope) = NumberExpression(value, scope, origin)

    override fun resolveThrownType(context: ResolutionContext): Type = Types.Nothing
    override fun resolveYieldedType(context: ResolutionContext): Type = Types.Nothing

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false
    override fun needsBackingField(methodScope: Scope): Boolean = false
    override fun splitsScope(): Boolean = false
    override fun isResolved(): Boolean = true
    override fun forEachExpression(callback: (Expression) -> Unit) {}

    override fun replaceLambdaFieldsWithClassFields(oldFields: List<Field>, newFields: List<Field>) = this

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {
        // println("resolving number, $expr, ${expr.resolvedType0}, ${context.targetType}")
        val type = TypeResolution.resolveType(context, this)
        val dst = block0.field(type, this)
        block0.add(SimpleNumber(dst, this))
        return flow0.withValue(dst, block0)
    }
}