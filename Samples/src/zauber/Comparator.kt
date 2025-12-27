package zauber

fun interface Comparator<V> {
    fun compare(a: V, b: V): Int
}