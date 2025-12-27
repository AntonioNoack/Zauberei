package zauberKt

import kotlin.math.max

class ArrayList<V>(capacity: Int) : MutableList<V> {

    // todo implement inserting constructors and methods from parameters with default values
    constructor() : this(16)

    val content = Array<V>(capacity)

    override var size: Int = 0
        private set

    override fun isEmpty(): Boolean = size == 0

    override fun get(index: Int): V = content[index]

    override fun set(index: Int, value: V): V {
        val prev = content[index]
        content[index] = value
        return prev
    }

    override fun add(element: V): Boolean {
        if (size == content.size) {
            val newSize = max(16, content.size * 2)
            content = content.copyOf(newSize)
        }
        content[size++] = element
        return true
    }
}