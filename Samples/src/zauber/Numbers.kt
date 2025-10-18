package zauber

interface Number {

}

class Int {
    operator fun plus(other: Int): Int = native("this + other")
    operator fun minus(other: Int): Int = native("this - other")
    operator fun times(other: Int): Int = native("this * other")
    operator fun div(other: Int): Int = native("this / other")
    operator fun rem(other: Int): Int = native("this % other")
}

class Long {
    operator fun plus(other: Long): Long = native("this + other")
    operator fun minus(other: Long): Long = native("this - other")
    operator fun times(other: Long): Long = native("this * other")
    operator fun div(other: Long): Long = native("this / other")
    operator fun rem(other: Long): Long = native("this % other")
}