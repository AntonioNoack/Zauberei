package zauber
import zauber.math.min

interface Iterator<V> {
    operator fun hasNext(): Boolean
    operator fun next(): V
}

interface ListIterator<V> : Iterator<V> {
    fun hasPrevious(): zauber.Boolean
    fun previous(): V

    fun nextIndex(): zauber.Int
    fun previousIndex(): zauber.Int
}

interface Iterable<V> {
    operator fun iterator(): Iterator<V>

    inline fun all(predicate: (V) -> Boolean): Boolean {
        for (element in this) {
            if (!predicate(element)) return false
        }
        return true
    }

    inline fun none(predicate: (V) -> Boolean): Boolean {
        for (element in this) {
            if (predicate(element)) return false
        }
        return true
    }

    inline fun any(predicate: (V) -> Boolean): Boolean {
        for (element in this) {
            if (predicate(element)) return true
        }
        return false
    }

    inline fun firstOrNull(): V? {
        for (element in this) {
            return element
        }
        return null
    }

    inline fun firstOrNull(predicate: (V) -> Boolean): V? {
        for (element in this) {
            if (predicate(element)) return element
        }
        return null
    }

    inline fun first(): V {
        return iterator().next()
    }

    inline fun lastOrNull(): V? {
        var value: V? = null
        for (element in this) {
            value = element
        }
        return value
    }

    inline fun lastOrNull(predicate: (V) -> Boolean): V? {
        var value: V? = null
        for (element in this) {
            if (predicate(element)) value = element
        }
        return value
    }

    inline fun last(): V {
        lateinit var value: V
        for (element in this) {
            value = element
        }
        return value
    }

    fun reduce(map: (V, V) -> V): V {
        var i = 1
        var result = this[0]
        while (i < size) {
            result = map(result, this[i])
            i++
        }
        return result
    }

    fun <R> map(map: (V) -> R): Array<R> {
        var i = 0
        var result = Array<V>(size)
        while (i < size) {
            result[i] = map(this[i])
            i++
        }
        return result
    }

    fun filter(predicate: (V) -> Boolean): Array<V> {
        var count = 0
        for (value in this) {
            if (predicate(value)) count++
        }
        val result = Array<V>(count)
        var i = 0; count = 0
        for (value in this) {
            if (predicate(this[i])) {
                result[count++] = this[i]
            }
            i++
        }
        return result
    }

}

interface List<V>: Iterable<V> {
    val size: Int
    fun get(index: Int): V

    override fun iterator(): ListIterator<V> = ListIteratorImpl(this)

    fun component1(): V = get(0)
    fun component2(): V = get(1)
}

interface MutableList<V>: List<V> {
    fun set(index: Int, value: V)
}

class ListIteratorImpl<V>(val list: List<V>): ListIterator<V> {
    var index = 0
    override fun hasNext() = index < list.size
    override fun nextIndex() = index
    override fun next(): V = list[index++]

    override fun hasPrevious() = index > 0
    override fun previousIndex() = index - 1
    override fun previous(): V = list[--index]
}

class Array<V>(override val size: Int): MutableList<V> {
    external override fun get(index: Int): V
    external override fun set(index: Int, value: V)

    fun copyOfRange(i0: Int, i1: Int): Array<V> {
        val clone = Array<V>(i1-i0)
        var i = i0
        while (i < i1) {
            clone[i - i0] = this[i]
            i++
        }
        return clone
    }

    operator fun plus(other: Array<V>): Array<V> {
        val newSize = size + other.size
        val result = copyOf(newSize)
        other.copyInto(result, size, 0, other.size)
        return result
    }

    fun copyInto(dst: Array<V>, dstStartIndex: Int) {
        copyInto(dst, dstStartIndex, 0, size)
    }

    fun copyInto(dst: Array<V>, dstStartOffset: Int, srcStartIndex: Int, srcEndIndex: Int) {
        val srcToDst = dstStartOffset - srcStartIndex
        for (i in srcStartIndex until srcEndIndex) {
            dst[srcToDst + i] = this[i]
        }
    }

    fun copyOf(newSize: Int): Array<V> {
        val clone = Array<V>(newSize)
        // todo zauber.math.min should not be necessary, because we import it!
        copyInto(clone, 0, 0, zauber.math.min(newSize, size))
        return clone
    }
}

fun <V> arrayOf(vararg vs: V): Array<V> = vs

// todo this cast should not be needed
fun <V> listOf(vararg vs: V): List<V> = vs as List<V>
fun <V> mutableListOf(vararg vs: V): MutableList<V> = vs

typealias ByteArray = Array<Byte>
typealias UByteArray = Array<UByte>
typealias ShortArray = Array<Short>
typealias UShortArray = Array<UShort>
typealias IntArray = Array<Int>
typealias UIntArray = Array<UInt>
typealias LongArray = Array<Long>
typealias ULongArray = Array<ULong>
typealias BooleanArray = Array<Boolean>
typealias HalfArray = Array<Half>
typealias FloatArray = Array<Float>
typealias DoubleArray = Array<Double>
