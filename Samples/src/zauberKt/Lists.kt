package zauberKt

import kotlin.math.max

fun <V> emptyList(): List<V> = Array(0)

interface List<V> {
    operator fun get(index: Int): V
    val size: Int

    fun withIndex(): List<IndexedValue<V>> {
        return List(size) {
            IndexedValue(it, this[it])
        }
    }

    fun filter(predicate: (V) -> Boolean): List<V> {
        val result = ArrayList<V>(size)
        for (e in this) {
            if (predicate(e)) {
                result.add(e)
            }
        }
        return result
    }
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
        if(size == content.size) {
            val newSize = max(16, content.size * 2)
            content = content.copyOf(newSize)
        }
        content[size++] = element
        return true
    }
}