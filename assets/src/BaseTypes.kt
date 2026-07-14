package zauber

open class Any {
    open fun toString(): String {
        return "@${hashCode()}"
    }

    open external fun hashCode(): Int

    open fun equals(other: Any?): Boolean = (this === other)
}

object Unit

enum class Nothing {}

enum class Boolean {
    FALSE,
    TRUE;

    fun toInt() = ordinal
    fun not(): Boolean = if (this) false else true

    override fun toString(): String {
        return if (this) "true" else "false"
    }
}

class Ref<V>(var value: V)

annotation class StrictLayout
