package zauber

import zauber.Double.Companion.EXPONENT_MASK
import zauber.Double.Companion.MANTISSA_MASK

external class Byte(val content: Byte) {
    fun inv(): Byte = xor((-1).toByte())
    fun inc(): Byte = (this + 1).toByte()
    fun dec(): Byte = (this - 1).toByte()
    fun unaryPlus() = toInt()
    fun unaryMinus() = -toInt()

    external fun plus(other: Int): Int
    external fun minus(other: Int): Int
    external fun times(other: Int): Int
    external fun div(other: Int): Int
    external fun rem(other: Int): Int

    external fun and(other: Byte): Byte
    external fun or(other: Byte): Byte
    external fun xor(other: Byte): Byte

    external fun compareTo(other: Byte): Int
    external fun equals(other: Byte): Boolean
    fun equals(other: Int): Boolean = toInt() == other
    override fun equals(other: Any?): Boolean = other is Byte && content == other
    override fun hashCode(): Int = toInt()

    external fun toByte(): Byte
    external fun toUByte(): UByte
    implicit external fun toShort(): Short
    external fun toUShort(): UShort
    implicit external fun toInt(): Int
    external fun toUInt(): UInt
    implicit external fun toLong(): Long
    external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    external fun toChar(): Char

    companion object {
        const val MIN_VALUE: Byte = -0x80
        const val MAX_VALUE: Byte = +0x7f
    }
}

external class UByte(val content: UByte) {
    fun inv(): UByte = xor(MAX_VALUE)
    fun inc(): UByte = (this + 1).toUByte()
    fun dec(): UByte = (this - 1).toUByte()
    fun unaryPlus() = toInt()
    fun unaryMinus() = -toInt()

    external fun plus(other: UInt): UInt
    external fun minus(other: UInt): UInt
    external fun times(other: UInt): UInt
    external fun div(other: UInt): UInt
    external fun rem(other: UInt): UInt

    external fun and(other: UByte): UByte
    external fun or(other: UByte): UByte
    external fun xor(other: UByte): UByte

    external fun compareTo(other: UByte): Int
    external fun equals(other: UByte): Boolean
    fun equals(other: UInt): Boolean = toUInt() == other
    override fun equals(other: Any?): Boolean = other is UByte && content == other
    override fun hashCode(): Int = toInt()

    external fun toByte(): Byte
    external fun toUByte(): UByte
    implicit external fun toShort(): Short
    implicit external fun toUShort(): UShort
    implicit external fun toInt(): Int
    implicit external fun toUInt(): UInt
    implicit external fun toLong(): Long
    implicit external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    implicit external fun toChar(): Char

    companion object {
        const val MIN_VALUE: UByte = 0
        const val MAX_VALUE: UByte = 0xff
    }
}

external class Short(val content: Short) {
    fun inv(): Short = xor((-1).toShort())
    fun inc(): Short = (this + 1).toShort()
    fun dec(): Short = (this - 1).toShort()
    fun unaryPlus() = toInt()
    fun unaryMinus() = -toInt()

    external fun plus(other: Int): Int
    external fun minus(other: Int): Int
    external fun times(other: Int): Int
    external fun div(other: Int): Int
    external fun rem(other: Int): Int

    external fun and(other: Short): Short
    external fun or(other: Short): Short
    external fun xor(other: Short): Short

    external fun compareTo(other: Short): Int
    external fun equals(other: Short): Boolean
    fun equals(other: Int): Boolean = toInt() == other
    override fun equals(other: Any?): Boolean = other is Short && content == other
    override fun hashCode(): Int = toInt()

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    implicit external fun toInt(): Int
    external fun toUInt(): UInt
    implicit external fun toLong(): Long
    external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    external fun toChar(): Char

    companion object {
        const val MIN_VALUE: Short = -0x8000
        const val MAX_VALUE: Short = +0x7fff
    }
}

external class UShort(val content: UShort) {
    fun inv(): UShort = xor(MAX_VALUE)
    fun inc(): UShort = (this + 1).toUShort()
    fun dec(): UShort = (this - 1).toUShort()
    fun unaryPlus() = toInt()
    fun unaryMinus() = -toInt()

    external fun plus(other: UInt): UInt
    external fun minus(other: UInt): UInt
    external fun times(other: UInt): UInt
    external fun div(other: UInt): UInt
    external fun rem(other: UInt): UInt

    external fun and(other: UShort): UShort
    external fun or(other: UShort): UShort
    external fun xor(other: UShort): UShort

    external fun compareTo(other: UShort): Int
    external fun equals(other: UShort): Boolean
    fun equals(other: UInt): Boolean = toUInt() == other
    override fun equals(other: Any?): Boolean = other is UShort && content == other
    override fun hashCode(): Int = toInt()

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    implicit external fun toInt(): Int
    implicit external fun toUInt(): UInt
    implicit external fun toLong(): Long
    implicit external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    implicit external fun toChar(): Char

    companion object {
        const val MIN_VALUE: UShort = 0
        const val MAX_VALUE: UShort = 0xffff
    }
}

external class Int(val content: Int) {
    fun inv(): Int = xor(-1)
    fun inc(): Int = this + 1
    fun dec(): Int = this - 1
    fun unaryPlus() = this
    fun unaryMinus() = 0 - this

    external fun plus(other: Int): Int
    external fun minus(other: Int): Int
    external fun times(other: Int): Int
    external fun div(other: Int): Int
    external fun rem(other: Int): Int
    external fun and(other: Int): Int
    external fun or(other: Int): Int
    external fun xor(other: Int): Int

    external fun compareTo(other: Int): Int
    external fun equals(other: Int): Boolean
    override fun equals(other: Any?): Boolean = other is Int && content == other
    override fun hashCode(): Int = this

    external fun shl(shift: Int): Int
    external fun shr(shift: Int): Int
    external fun ushr(shift: Int): Int
    external fun rotateLeft(shift: Int): Int = shl(shift) or ushr(32 - shift)
    external fun rotateRight(shift: Int): Int = ushr(shift) or shl(32 - shift)

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    implicit external fun toLong(): Long
    external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    external fun toChar(): Char

    companion object {
        const val MIN_VALUE: Int = -0x8000_0000
        const val MAX_VALUE: Int = +0x7fff_ffff
    }
}

external class UInt(val content: UInt) {
    fun inv(): UInt = xor(MAX_VALUE)
    fun inc(): UInt = this + 1
    fun dec(): UInt = this - 1
    fun unaryPlus() = toInt()
    fun unaryMinus() = -toInt()

    external fun plus(other: UInt): UInt
    external fun minus(other: UInt): UInt
    external fun times(other: UInt): UInt
    external fun div(other: UInt): UInt
    external fun rem(other: UInt): UInt
    external fun and(other: UInt): UInt
    external fun or(other: UInt): UInt
    external fun xor(other: UInt): UInt

    external fun compareTo(other: UInt): Int
    external fun equals(other: UInt): Boolean
    override fun equals(other: Any?): Boolean = other is UInt && content == other
    override fun hashCode(): Int = toInt()

    external fun shl(shift: Int): UInt
    external fun shr(shift: Int): UInt
    external fun ushr(shift: Int): UInt = shr(shift)
    external fun rotateLeft(shift: Int): UInt = shl(shift) or ushr(32 - shift)
    external fun rotateRight(shift: Int): UInt = shr(shift) or shl(32 - shift)

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    implicit external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double
    external fun toChar(): Char

    companion object {
        const val MIN_VALUE: UInt = 0
        const val MAX_VALUE: UInt = 0xffff_ffff
    }
}

external class Long(val content: Long) {
    fun inv(): Long = xor(-1L)
    fun inc(): Long = this + 1
    fun dec(): Long = this - 1
    fun unaryPlus() = this
    fun unaryMinus() = 0L - this

    external fun plus(other: Long): Long
    external fun minus(other: Long): Long
    external fun times(other: Long): Long
    external fun div(other: Long): Long
    external fun rem(other: Long): Long
    external fun and(other: Long): Long
    external fun or(other: Long): Long
    external fun xor(other: Long): Long

    external fun compareTo(other: Long): Int
    external fun equals(other: Long): Boolean
    fun equals(other: Int): Boolean = this == other.toLong()
    override fun equals(other: Any?): Boolean = other is Long && content == other
    override fun hashCode(): Int = ushr(32).toInt() * 31 + toInt()

    external fun shl(shift: Int): Long
    external fun shr(shift: Int): Long
    external fun ushr(shift: Int): Long
    external fun rotateLeft(shift: Int): Long = shl(shift) or ushr(64 - shift)
    external fun rotateRight(shift: Int): Long = ushr(shift) or shl(32 - shift)

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double

    companion object {
        const val MIN_VALUE: Long = -0x8000_0000_0000_0000
        const val MAX_VALUE: Long = +0x7fff_ffff_ffff_ffff
    }
}

external class ULong(val content: ULong) {
    fun inv(): ULong = xor(MAX_VALUE)
    fun inc(): ULong = this + 1
    fun dec(): ULong = this - 1
    fun unaryPlus() = toLong()
    fun unaryMinus() = -toLong()

    external fun plus(other: ULong): ULong
    external fun minus(other: ULong): ULong
    external fun times(other: ULong): ULong
    external fun div(other: ULong): ULong
    external fun rem(other: ULong): ULong
    external fun and(other: ULong): ULong
    external fun or(other: ULong): ULong
    external fun xor(other: ULong): ULong

    external fun compareTo(other: ULong): Int
    external fun equals(other: ULong): Boolean
    override fun equals(other: Any?): Boolean = other is ULong && content == other
    override fun hashCode(): Int = ushr(32).toInt() * 31 + toInt()

    external fun shl(shift: Int): ULong
    external fun shr(shift: Int): ULong
    external fun ushr(shift: Int): ULong
    external fun rotateLeft(shift: Int): ULong = shl(shift) or ushr(64 - shift)
    external fun rotateRight(shift: Int): ULong = shr(shift) or shl(64 - shift)

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    external fun toULong(): ULong
    implicit external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double

    companion object {
        const val MIN_VALUE: ULong = 0
        const val MAX_VALUE: ULong = 0xffff_ffff_ffff_ffff
    }
}

external class Half(val content: Half) {
    fun inc(): Half = this + 1h
    fun dec(): Half = this - 1h
    fun unaryPlus() = this
    fun unaryMinus() = 0h - this

    external fun plus(other: Half): Half
    external fun minus(other: Half): Half
    external fun times(other: Half): Half
    external fun div(other: Half): Half
    external fun rem(other: Half): Half

    external fun compareTo(other: Half): Int
    external fun equals(other: Half): Boolean
    override fun equals(other: Any?): Boolean = other is Half && content == other
    override external fun hashCode(): Int
    external fun toBits(): Short

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    external fun toULong(): ULong
    external fun toHalf(): Half
    implicit external fun toFloat(): Float
    implicit external fun toDouble(): Double

    fun isNaN(): Boolean {
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        val frac = bits and MANTISSA_MASK
        return (exp == EXPONENT_MASK) && (frac != 0)
    }

    external fun isFinite(): Boolean {
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        return exp != EXPONENT_MASK
    }

    fun isInfinite(): Boolean = !isFinite()

    companion object {
        external fun fromBits(bits: Short): Half

        const val EXPONENT_MASK: Short = 0x7C00 // 5 bits
        const val MANTISSA_MASK: Short = 0x03FF // 10 bits

        const val POSITIVE_INFINITY: Half = 1h / 0h
        const val NEGATIVE_INFINITY: Half = -POSITIVE_INFINITY
    }
}

external class Float(val content: Float) {
    external fun neg(): Float
    fun inc(): Float = this + 1f
    fun dec(): Float = this - 1f
    fun unaryPlus() = this
    fun unaryMinus() = 0f - this

    external fun plus(other: Float): Float
    external fun minus(other: Float): Float
    external fun times(other: Float): Float
    external fun div(other: Float): Float
    external fun rem(other: Float): Float

    external fun compareTo(other: Float): Int
    external fun equals(other: Float): Boolean
    override fun equals(other: Any?): Boolean = other is Float && content == other
    override external fun hashCode(): Int
    external fun toBits(): Int

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    external fun toULong(): ULong
    external fun toHalf(): Half
    external fun toFloat(): Float
    implicit external fun toDouble(): Double

    fun isNaN(): Boolean {
        // todo properly define the masks!
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        val frac = bits and MANTISSA_MASK
        return (exp == EXPONENT_MASK) && (frac != 0)
    }

    external fun isFinite(): Boolean {
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        return exp != EXPONENT_MASK
    }

    fun isInfinite(): Boolean = !isFinite()

    companion object {
        external fun fromBits(bits: Int): Float

        const val EXPONENT_MASK = 0x7F800000
        const val MANTISSA_MASK = 0x007FFFFF

        const val POSITIVE_INFINITY: Float = 1f / 0f
        const val NEGATIVE_INFINITY: Float = -POSITIVE_INFINITY
    }
}

external class Double(val content: Double) {
    external fun neg(): Double
    fun inc(): Double = this + 1.0
    fun dec(): Double = this - 1.0
    fun unaryPlus() = this
    fun unaryMinus() = 0.0 - this

    external fun plus(other: Double): Double
    external fun minus(other: Double): Double
    external fun times(other: Double): Double
    external fun div(other: Double): Double
    external fun rem(other: Double): Double

    external fun compareTo(other: Double): Int
    external fun equals(other: Double): Boolean
    override fun equals(other: Any?): Boolean = other is Double && content == other
    override external fun hashCode(): Int
    external fun toBits(): Long

    external fun toByte(): Byte
    external fun toUByte(): UByte
    external fun toShort(): Short
    external fun toUShort(): UShort
    external fun toInt(): Int
    external fun toUInt(): UInt
    external fun toLong(): Long
    external fun toULong(): ULong
    external fun toHalf(): Half
    external fun toFloat(): Float
    external fun toDouble(): Double

    fun isNaN(): Boolean {
        // todo properly define the masks!
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        val frac = bits and MANTISSA_MASK
        return (exp == EXPONENT_MASK) && (frac != 0L)
    }

    external fun isFinite(): Boolean {
        val bits = toBits()
        val exp = bits and EXPONENT_MASK
        return exp != EXPONENT_MASK
    }

    fun isInfinite(): Boolean = !isFinite()

    companion object {
        external fun fromBits(bits: Long): Double

        const val EXPONENT_MASK = 0x7FF0000000000000L
        const val MANTISSA_MASK = 0x000FFFFFFFFFFFFFL

        const val POSITIVE_INFINITY: Double = 1.0 / 0.0
        const val NEGATIVE_INFINITY: Double = -POSITIVE_INFINITY
    }
}
