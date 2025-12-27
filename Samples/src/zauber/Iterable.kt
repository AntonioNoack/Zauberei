package zauber

interface Iterable<V> {
    operator fun iterator(): Iterator<V>
}