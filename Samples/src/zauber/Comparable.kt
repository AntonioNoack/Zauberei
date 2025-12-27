package zauber

interface Comparable<V> {
    fun compareTo(other: V): Int

    companion object {
        fun <V : Comparable<V>> V.clamp(min: V, max: V): V {
            return if (this < min) min else if (this < max) this else max
        }

        fun <V : Comparable<V>> V.min(other: V): V {
            return if (this < other) this else other
        }

        fun <V : Comparable<V>> V.max(other: V): V {
            return if (this > other) this else other
        }
    }
}