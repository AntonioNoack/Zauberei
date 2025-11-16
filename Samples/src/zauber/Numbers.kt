package zauber

interface Number {
    operator fun plus(other: Self): Self
    operator fun minus(other: Self): Self
    operator fun times(other: Self): Self
    operator fun div(other: Self): Self
    operator fun rem(other: Self): Self
}

class Int: Number {
    operator fun plus(other: Int): Int = native("this + other")
    operator fun minus(other: Int): Int = native("this - other")
    operator fun times(other: Int): Int = native("this * other")
    operator fun div(other: Int): Int = native("this / other")
    operator fun rem(other: Int): Int = native("this % other")

    fun toInt(): Int = this
    fun toLong(): Long = native("s64(this)")
    fun toFloat(): Float = native("f32(this)")
    fun toDouble(): Double = native("f64(this)")
}

class Long: Number {
    operator fun plus(other: Long): Long = native("this + other")
    operator fun minus(other: Long): Long = native("this - other")
    operator fun times(other: Long): Long = native("this * other")
    operator fun div(other: Long): Long = native("this / other")
    operator fun rem(other: Long): Long = native("this % other")

    fun toInt(): Int = native("s32(this)")
    fun toLong(): Long = this
    fun toFloat(): Float = native("f32(this)")
    fun toDouble(): Double = native("f64(this)")
}

class Float: Number {
    operator fun plus(other: Float): Float = native("this + other")
    operator fun minus(other: Float): Float = native("this - other")
    operator fun times(other: Float): Float = native("this * other")
    operator fun div(other: Float): Float = native("this / other")
    operator fun rem(other: Float): Float = native("this % other")

    fun toInt(): Int = native("s32(this)")
    fun toLong(): Long = native("s64(this)")
    fun toFloat(): Float = this
    fun toDouble(): Double = native("f64(this)")
}

class Double: Number {
    operator fun plus(other: Double): Double = native("this + other")
    operator fun minus(other: Double): Double = native("this - other")
    operator fun times(other: Double): Double = native("this * other")
    operator fun div(other: Double): Double = native("this / other")
    operator fun rem(other: Double): Double = native("this % other")

    fun toInt(): Int = native("s32(this)")
    fun toLong(): Long = native("s64(this)")
    fun toFloat(): Float = native("f32(this)")
    fun toDouble(): Double = this
}