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

}

interface CharSequence {}

class String(val content: ByteArray) {
    // todo all what we need: charAt(), substring(), trim(), toX(), toXOrNull()

    val length get() = content.size
    val size get() = content.size

    fun get(index: Int) = content[index].toChar()

    fun plus(other: String): String {
        return String(content + other.content)
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

    fun append(value: UInt): StringBuilder {
        var v = value
        val i0 = size
        while (v >= 10u) {
            append('0' + (v % 10u))
            v = v / 10u
        }
        append('0' + v)
        return this
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
        var v = value
        val i0 = size
        while (v >= 10) {
            append('0' + (v % 10))
            v = v / 10
        }
        append('0' + v)
        return this
    }

    // separated, so we don't need to work with i64 if we don't have to
    fun append(value: Long): StringBuilder {
        if (value == Long.MIN_VALUE) return append("-9223372036854775808")
        if (value < 0) {
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
            else -> appendFloaty<Float>(value.toFloat(), 3)
        }
        return this
    }

    fun append(value: Float): StringBuilder {
        when {
            value.isNaN() -> append("NaN")
            value == Float.POSITIVE_INFINITY -> append("Infinity")
            value == Float.NEGATIVE_INFINITY -> append("-Infinity")
            else -> appendFloaty<Float>(value, 7)
        }
        return this
    }

    fun append(value: Double): StringBuilder {
        when {
            value.isNaN() -> append("NaN")
            value == Double.POSITIVE_INFINITY -> append("Infinity")
            value == Double.NEGATIVE_INFINITY -> append("-Infinity")
            else -> appendFloaty<Double>(value, 16)
        }
        return this
    }

    private fun <F> appendFloaty(value: F) {
        // todo append float
        append("some float")
    }

    override fun toString(): String = String(buffer.copyOf(size))

}