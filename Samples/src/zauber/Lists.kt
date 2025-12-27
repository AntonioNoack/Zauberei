package zauber

fun <V> emptyList(): List<V> = Array(0)

interface List<V> {
    operator fun get(index: Int): V

    val size: Int
    fun isEmpty(): Boolean
}

fun List<*>.indices(): IntRange {
    return 0 until size
}

fun <V> List<V>.firstOrNull(): V? {
    return if (isEmpty()) null else this[0]
}

fun <V> List<V>.firstOrNull(predicate: (V) -> Boolean): V? {
    for (i in indices) {
        val element = this[i]
        if (predicate(element)) return element
    }
    return null
}

val List<*>.lastIndex: Int
    get() = size - 1

fun <V> List<V>.lastOrNull(): V? {
    return if (isEmpty()) null else this[lastIndex]
}

fun <V> List<V>.lastOrNull(predicate: (V) -> Boolean): V? {
    for (i in indices.reversed()) {
        val element = this[i]
        if (predicate(element)) return element
    }
    return null
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

inline fun <V, R> List<V>.mapIndexed(transform: (Int, V) -> R): List<R> {
    return List<R>(size) { transform(it, this[it]) }
}

inline fun <V, R> List<V>.map(transform: (V) -> R): List<R> {
    return List<R>(size) { transform(this[it]) }
}

data class IndexedValue<V>(val index: Int, val value: V)

interface MutableList<V> : List<V> {
    operator fun set(index: Int, value: V): V
    fun add(element: V): Boolean
}
