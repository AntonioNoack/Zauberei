package me.anno.zauberei.utils

class Lazy2<V>(val generator: () -> V) {

    enum class State {
        FRESH,
        GENERATING,
        HAS_VALUE
    }

    var state = State.FRESH
    private var valueI: V? = null

    val value: V
        get() = when (state) {
            State.FRESH -> {
                state = State.GENERATING
                valueI = generator()
                state = State.HAS_VALUE
                valueI as V
            }
            State.HAS_VALUE -> {
                valueI as V
            }
            State.GENERATING -> {
                throw IllegalStateException("Recursive dependency")
            }
        }

}