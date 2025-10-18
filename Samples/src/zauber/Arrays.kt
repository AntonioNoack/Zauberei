package zauber

class Array<V>(override val size: Int) : List<V> {
    override operator fun get(index: Int): V {
        TODO()
    }

    operator fun set(index: Int, value: V) {
        TODO()
    }
}

typealias IntArray = Array<Int>
typealias FloatArray = Array<Float>
typealias LongArray = Array<Long>
typealias DoubleArray = Array<Double>
typealias ByteArray = Array<Byte>
typealias ShortArray = Array<Short>
typealias CharArray = Array<Char>