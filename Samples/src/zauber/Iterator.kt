package zauber

interface Iterator<V> {
    fun hasNext(): Boolean
    fun next(): V
}