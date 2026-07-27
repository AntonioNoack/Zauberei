package me.anno.utils

object FloatFormatter {

    private var StringBuilder.size: Int
        get() = length
        set(value) {
            setLength(value)
        }

    fun StringBuilder.appendDouble(value: Double, maxPrecision: Int): StringBuilder {
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

        val asLong = value.toLong()
        val len0 = size // length before appending; e.g. 0
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

        return this
    }

    private fun StringBuilder.roundUpDouble(len0: Int, asLong: Long) {
        // we must add one
        val buffer = this
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
                    buffer[size - 1]++
                    return
                }
            }
        }
    }


}