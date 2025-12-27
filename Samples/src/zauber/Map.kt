package zauber

interface Map<K, V> {
    operator fun get(key: K): V?
}

interface MutableMap<K, V> : Map<K, V> {
    operator fun set(key: K, value: V): V?
}

private class TrivialMap<K, V>(vararg val entries: Pair<K, V>) : Map<K, V> {
    override fun get(key: K): V? {
        for (entry in entries) {
            if (key == entry.first) return entry.second
        }
        return null
    }
}

fun <K, V> mapOf(vararg entries: Pair<K, V>): Map<K, V> {
    return TrivialMap<K, V>(*entries)
}