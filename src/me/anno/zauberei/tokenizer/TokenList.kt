package me.anno.zauberei.tokenizer

// could be placed into a token list...
class TokenList(val src: String) {

    var size = 0
        private set

    val indices get() = 0 until size

    private var tokenTypes = ByteArray(16)
    private var offsets = IntArray(32)

    fun getType(i: Int): TokenType {
        return TokenType.entries[tokenTypes[i].toInt()]
    }

    fun getI0(i: Int) = offsets[i * 2]
    fun getI1(i: Int) = offsets[i * 2 + 1]

    fun add(type: TokenType, i0: Int, i1: Int) {
        if (size == tokenTypes.size) {
            tokenTypes = tokenTypes.copyOf(size * 2)
            offsets = offsets.copyOf(size * 4)
        }

        if (size > 0 && type == TokenType.SYMBOL &&
            getType(size - 1) == TokenType.SYMBOL &&
            i0 == offsets[size * 2 - 1]
        ) {
            // todo only accept a symbol if the previous is not =, or the current one is =, too
            // extend symbol
            offsets[size * 2 - 1] = i1
        } else {
            tokenTypes[size] = type.ordinal.toByte()
            offsets[size * 2] = i0
            offsets[size * 2 + 1] = i1
            size++
        }
    }

    fun equals(i: Int, type: TokenType): Boolean = (getType(i) == type)
    fun equals(i: Int, str: String): Boolean {
        val i0 = getI0(i)
        val i1 = getI1(i)
        if (i1 - i0 != str.length) return false
        return str.indices.all { strIndex ->
            str[strIndex] == src[i0 + strIndex]
        }
    }

    fun equals(i: Int, type: TokenType, str: String): Boolean =
        equals(i, type) && equals(i, str)

    override fun toString(): String {
        return (0 until size).map { i ->
            "${getType(i)}(${getString(i)})"
        }.toString()
    }

    fun getString(i: Int): String {
        return src.substring(getI0(i), getI1(i))
    }

}