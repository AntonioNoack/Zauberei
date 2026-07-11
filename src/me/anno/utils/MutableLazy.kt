package me.anno.utils

class MutableLazy<V : Any>(private val generator: () -> V) : Lazy<V> {

    private var actualValue: V? = null

    override var value: V
        get() = actualValue ?: generate()
        set(value) {
            actualValue = value
        }

    private fun generate(): V {
        val value = generator()
        actualValue = value
        return value
    }

    override fun isInitialized(): Boolean = actualValue != null
}