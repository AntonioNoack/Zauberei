package zauberKt

// todo is this a MutableList? Kind of yes, kind of no...
class Array<V>(override val size: Int) : List<V> {
    external override operator fun get(index: Int): V
    external operator fun set(index: Int, value: V)

    external fun copyOf(): Array<V>
    external fun copyOf(newSize: Int): Array<V>
    override fun isEmpty(): Boolean = size == 0
}

typealias IntArray = Array<Int>
typealias FloatArray = Array<Float>
typealias LongArray = Array<Long>
typealias DoubleArray = Array<Double>
typealias ByteArray = Array<Byte>
typealias ShortArray = Array<Short>
typealias CharArray = Array<Char>