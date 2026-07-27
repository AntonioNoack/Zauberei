package zauber
import zauber.math.max

external class Char(val content: Char) {
    external fun compareTo(other: Char): Int
    fun toByte() = toInt().toByte()
    fun toUByte() = toInt().toUByte()
    fun toShort() = toInt().toShort()
    fun toUShort() = toInt().toUShort()
    external fun toInt(): Int
    fun toUInt() = toInt().toUInt()
    fun toLong() = toInt().toLong()
    fun toULong() = toInt().toULong()

    fun plus(other: Int): Char = (toInt() + other).toChar()
    fun plus(other: UInt): Char = (toUInt() + other).toChar()

    fun isWhitespace() = this in " \t\r\n"

}

interface CharSequence {}

class String(val content: ByteArray) {
    // todo all what we need: charAt(), substring(), trim(), toX(), toXOrNull()

    val length get() = content.size
    val size get() = content.size

    fun get(index: Int) = content[index].toChar()

    override fun toString(): String = this

    fun plus(other: String): String {
        return String(content + other.content)
    }

    operator fun <V> plus(other: V): String {
        return plus(other?.toString() ?: "null")
    }

    fun trim(): String {
        var i0 = 0
        var i1 = length-1
        if (i1 == -1) return ""
        while(this[i0].isWhitespace()) {
            if (i0 == i1) return ""
            i0++
        }
        while(this[i1].isWhitespace()) {
            i1--
        }
        return substring(i0,i1+1)
    }

    fun substring(i0: Int, i1: Int): String {
        // if (i0 == i1) return ""
        // if (i0 == 0 && i1 == length) return this
        return String(content.copyOfRange(i0, i1))
    }

    fun contains(char: Char): Boolean {
        // todo complex chars must compare two or three values!
        var i = 0
        while (i < length) {
            if (this[i] == char.toByte()) return true
            i++
        }
        return false
    }
}

@StrictLayout
class StringBuilder(capacity: Int = 16): CharSequence {

    companion object {
        // todo bug: this should not be necessary, we import zauber.math.max!
        private fun max(a: Int, b: Int): Int {
            return if(a > b) a else b
        }
    }

    private val buffer = ByteArray(max(capacity, 4))
    private var size = 0

    fun ensureExtraCapacity(extra: Int): StringBuilder {
        if (size + extra >= buffer.size) {
            buffer = buffer.copyOf(buffer.size + max(size, extra))
        }
        return this
    }

    fun append(char: Char): StringBuilder {
        // todo if high bits are set, encode this...
        // todo if we have a 17+ bit value, this gets more complicated...
        ensureExtraCapacity(1)
        buffer[size++] = char.toByte()
        return this
    }

    fun append(value: String): StringBuilder {
        val bytes = value.content
        ensureExtraCapacity(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
        return this
    }

    fun append(value: Byte) = append(value.toInt())
    fun append(value: UByte) = append(value.toUInt())
    fun append(value: Short) = append(value.toInt())
    fun append(value: UShort) = append(value.toUInt())
    fun append(value: Boolean) = append(value.toString())

    fun append(value: UInt): StringBuilder {
        val l0 = size
        var v = value
        val i0 = size
        while (v >= 10u) {
            append('0' + (v % 10u))
            v = v / 10u
        }
        append('0' + v)
        reverse(l0, size)
        return this
    }
    
    fun reverse(i0: Int, i1: Int) {
        var i = i0
        var j = i1 - 1
        while (i < j) {
            val tmp = buffer[i]
            buffer[i] = buffer[j]
            buffer[j] = tmp

            i++
            j--
        }
    }

    // separated, so we don't need to work with i64 if we don't have to
    fun append(value: Int): StringBuilder {
        if (value == Int.MIN_VALUE) return append("-2147483648")
        if (value < 0) {
            append('-')
            append((-value).toUInt())
        } else {
            append(value.toUInt())
        }
        return this
    }

    fun append(value: ULong): StringBuilder {
        val l0 = size
        var v = value
        val i0 = size
        while (v >= 10ul) {
            append('0' + (v % 10ul).toInt())
            v = v / 10ul
        }
        append('0' + v.toInt())
        reverse(l0, size)
        return this
    }

    // separated, so we don't need to work with i64 if we don't have to
    fun append(value: Long): StringBuilder {
        if (value == Long.MIN_VALUE) return append("-9223372036854775808")
        if (value < 0l) {
            append('-')
            append((-value).toULong())
        } else {
            append(value.toULong())
        }
        return this
    }

    fun append(value: Half): StringBuilder {
        when {
            value.isNaN() -> append("NaN")
            value == Half.POSITIVE_INFINITY -> append("Infinity")
            value == Half.NEGATIVE_INFINITY -> append("-Infinity")
            else -> appendDouble(value.toDouble(), 3)
        }
        return this
    }

    fun append(value: Float): StringBuilder {
        when {
            value.isNaN() -> append("NaN")
            value == Float.POSITIVE_INFINITY -> append("Infinity")
            value == Float.NEGATIVE_INFINITY -> append("-Infinity")
            else -> appendDouble(value.toDouble(), 7)
        }
        return this
    }

    fun append(value: Double): StringBuilder {
        when {
            value.isNaN() -> append("NaN")
            value == Double.POSITIVE_INFINITY -> append("Infinity")
            value == Double.NEGATIVE_INFINITY -> append("-Infinity")
            else -> appendDouble(value, 16)
        }
        return this
    }

    private fun appendDouble(value: Double, maxPrecision: Int) {
        var value = value
        if (value < 0.0) {
            value = -value
            append('-')
        }

        var exponent = 0
        if (value > 1e9) {
            while (value >= 1e100) {
                value *= 1e-99
                exponent += 99
            }
            while (value >= 1e10) {
                value *= 1e-9
                exponent += 9
            }
            while (value >= 10.0) {
                value *= 0.1
                exponent++
            }
        }

        if (value < 1e-9) {
            while (value < 1e-100) {
                value *= 1e100
                exponent -= 100
            }
            while (value < 1e-10) {
                value *= 1e10
                exponent -= 10
            }
            while (value < 1.0) {
                value *= 10.0
                exponent--
            }
        }

        var asLong = value.toLong()
        var len0 = size // length before appending; e.g. 0
        append(asLong)

        val len1 = size
        var precision = maxPrecision + len0 - len1 // e.g. + 0 - 3
        value -= asLong

        append('.')
        do {
            value *= 10.0
            val asInt = value.toInt()
            append('0' + asInt)
            value -= asInt
            precision--
        } while (value > 0.0 && precision > 0)

        if (value >= 0.5) {
            // round up
            roundUpDouble(len0, asLong)
        } else {
            // trim trailing zeros
            val len1i = len1 + 2 // +2 to retain at least one zero
            while (size > len1i && endsWith('0')) {
                size--
            }
        }

        if (exponent != 0) {
            append('e')
            append(exponent)
        }
    }

    private fun roundUpDouble(len0: Int, asLong: Long) {
        // we must add one
        val buffer = buffer
        while (size > len0) { // condition just in case
            val digit = buffer[size - 1]
            if (digit == '9') {
                // continue loop
                size--
            } else {
                if (digit == '.') {
                    // we must increment the integer
                    size = len0
                    append(asLong + 1)
                    append(".0")
                    return
                } else {
                    buffer[size-1]++
                    return
                }
            }
        }
    }

    fun endsWith(char: Char): Boolean {
        return size > 0 && buffer[size-1] == char
    }

    fun clear() {
        size = 0
    }

    fun <V> append(value: V): StringBuilder {
        return append(value?.toString() ?: "null")
    }

    override fun toString(): String = String(buffer.copyOf(size))

}