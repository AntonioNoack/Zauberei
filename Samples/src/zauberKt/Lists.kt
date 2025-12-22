package zauberKt

import kotlin.math.max

fun <V> emptyList(): List<V> = Array(0)

interface List<V> {
    operator fun get(index: Int): V
    val size: Int

}

fun <V> List<V>.withIndex(): List<IndexedValue<V>> {
    return List(size) {
        IndexedValue(it, this[it])
    }
}

inline fun <V> List<V>.filter(predicate: (V) -> Boolean): List<V> {
    val result = ArrayList<V>(size)
    for (i in indices) {
        val element = this[i]
        if (predicate(element)) {
            result.add(element)
        }
    }
    return result
}

inline fun <V, R> List<V>.mapIndexed(transform: (Int, V) -> Int): List<R> {
    return List<R>(size) { transform(it, this[it]) }
}

data class IndexedValue<V>(val index: Int, val value: V)

interface MutableList<V> : List<V> {
    operator fun set(index: Int, value: V): V
}

class ArrayList<V>(capacity: Int) : MutableList<V> {

    // todo implement inserting constructors and methods from parameters with default values
    constructor() : this(16)

    val content = Array<V>(capacity)

    override var size: Int = 0
        private set

    override fun get(index: Int): V = content[index]

    override fun set(index: Int, value: V): V {
        val prev = content[index]
        content[index] = value
        return prev
    }

    fun add(element: V): Boolean {
        if (size == content.size) {
            val newSize = max(16, content.size * 2)
            content = content.copyOf(newSize)
        }
        content[size++] = element
        return true
    }
}