package me.anno.utils

import me.anno.utils.FloatFormatter.appendDouble

@JvmInline
value class Half(val binary: Short) : Comparable<Half> {
    constructor(float: Float) : this(float32ToFloat16(float).toShort())

    fun isFinite(): Boolean = toFloat().isFinite()
    fun isNaN(): Boolean = toFloat().isNaN()
    fun toFloat(): Float = float16ToFloat32(binary.toInt())
    fun toDouble() = toFloat().toDouble()

    operator fun plus(rhs: Half) = Half(toFloat() + rhs.toFloat())
    operator fun minus(rhs: Half) = Half(toFloat() - rhs.toFloat())
    operator fun times(rhs: Half) = Half(toFloat() * rhs.toFloat())
    operator fun div(rhs: Half) = Half(toFloat() / rhs.toFloat())
    operator fun rem(rhs: Half) = Half(toFloat() % rhs.toFloat())

    override fun compareTo(other: Half): Int {
        return toFloat().compareTo(other.toFloat())
    }

    // todo check that all values of toString().toHalf() return the same Half
    override fun toString(): String {
        val asFloat = toFloat()
        if (!asFloat.isFinite()) return asFloat.toString()
        return StringBuilder(8)
            .appendDouble(toDouble(), 3)
            .toString()
    }

    companion object {

        /**
         * Smallest, non-zero, positive value, 5.9604645E-8
         * */
        const val FP16_MIN_VALUE = 1

        /**
         * Largest, finite positive value, 65504
         * */
        const val FP16_MAX_VALUE = 0x7bff

        /**
         * +Inf for fp16
         * */
        const val FP16_POSITIVE_INFINITY = 0x7c00

        /**
         * -Inf for fp16
         * */
        const val FP16_NEGATIVE_INFINITY = 0xfc00

        // by x4u on https://stackoverflow.com/a/6162687/4979303
        @JvmStatic
        fun float16ToFloat32(bits: Int): Float {
            var mantissa = bits and 0x03ff // 10 bits mantissa
            var exponent = bits and 0x7c00 // 5 bits exponent
            if (exponent == 0x7c00) {
                exponent = 0x3fc00 // NaN/Inf
            } else if (exponent != 0) {// normalized value
                exponent += 0x1c000 // exp - 15 + 127
                /*if (mantissa == 0 && exponent > 0x1c400) {// smooth transition <- better general behavior at the cost of losing integer-ness
                    return Float.fromBits((bits and 0x8000).shl(16) or (exponent shl 13) or 0x3ff)
                }*/
            } else if (mantissa != 0) {// && exp==0 -> subnormal
                exponent = 0x1c400 // make it normal
                do {
                    mantissa = mantissa shl 1 // mantissa * 2
                    exponent -= 0x400 // decrease exp by 1
                } while (mantissa and 0x400 == 0) // while not normal
                mantissa = mantissa and 0x3ff // discard subnormal bit
            } // else +/-0 -> +/-0
            // combine all parts
            val sign = (bits and 0x8000).shl(16) // sign << (31 - 15)
            val value = ((exponent or mantissa) shl 13) // value << ( 23 - 10 )
            return Float.fromBits(sign or value)
        }

        @JvmStatic
        @Suppress("unused")
        fun float32ToFloat16(value: Float): Int {
            val fp32 = value.toRawBits()
            val sign = (fp32.ushr(16)).and(0x8000) // sign only
            var v = (fp32 and 0x7fffffff) + 0x1000 // rounded value
            return if (v >= 0x47800000) { // might be or become NaN/Inf; avoid Inf due to rounding
                if (fp32 and 0x7fffffff >= 0x47800000) { // is or must become NaN/Inf
                    if (v < 0x7f800000) sign.or(0x7c00) // remains +/-Inf or NaN
                    else sign.or(0x7c00).or(fp32.and(0x007fffff).ushr(13)) // make it +/-Inf
                    // keep NaN (and Inf) bits
                } else sign.or(0x7bff) // unrounded not quite Inf
            } else if (v >= 0x38800000) { // remains normalized value
                sign or (v - 0x38000000 ushr 13) // exp - 127 + 15
            } else if (v < 0x33000000) { // too small for subnormal
                sign // becomes +/-0
            } else {
                v = fp32 and 0x7fffffff ushr 23 // tmp exp for subnormal calc
                sign or (((fp32 and 0x7fffff or 0x800000) +// add subnormal bit
                        (0x800000.ushr(v - 102))) // round depending on cut off
                    .ushr(126 - v)) // div by 2^(1-(exp-127+15)) and >> 13 | exp=0
            }
        }

        @JvmStatic
        fun Float.toHalf() = Half(this)

        @JvmStatic
        fun Double.toHalf() = Half(toFloat())

    }
}