package zauber

fun <V> emptyList(): List<V> = ArrayList(0)

interface List<V> {
    operator fun get(index: Int): V
    val size: Int
}

interface MutableList<V> : List<V> {
    operator fun set(index: Int, value: V): V
}

class ArrayList<V>(capacity: Int) : MutableList<V> {

    val content = Array<V>(capacity)

    override var size = 0
        private set

    override fun get(index: Int): V = content[index]

    override fun set(index: Int, value: V): V {
        val prev = content[index]
        content[index] = value
        return prev
    }
}