package zauber

class String(
    private val content: ByteArray,
    private val offset: Int,
    override val size: Int
) : CharSequence {

    constructor(content: ByteArray): this(content, 0, content.size)

    override fun get(index: Int): Char {
        check(index in 0 until size)
        return content[offset + index].toInt().and(255).toChar()
    }

    fun substring(startIndex: Int): String = substring(startIndex, size)
    fun substring(startIndex: Int, endIndex: Int): String {
        check(startIndex <= endIndex)
        check(startIndex >= 0)
        check(endIndex <= size)
        return String(content, startIndex + offset, endIndex - startIndex)
    }
}

fun check(b: Boolean) {
    if (!b) throw IllegalStateException()
}