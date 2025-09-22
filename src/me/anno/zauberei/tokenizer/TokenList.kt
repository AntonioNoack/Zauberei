package me.anno.zauberei.tokenizer

// could be placed into a token list...
class TokenList(val src: String, val fileName: String) {

    var size = 0
        private set

    val indices get() = 0 until size

    private var tokenTypes = ByteArray(16)
    private var offsets = IntArray(32)

    fun <R> pushCall(i: Int, readImpl: () -> R): R {
        return push(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL, readImpl)
    }

    fun <R> pushBlock(i: Int, readImpl: () -> R): R {
        return push(i, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK, readImpl)
    }

    fun <R> pushArray(i: Int, readImpl: () -> R): R {
        return push(i, TokenType.OPEN_ARRAY, TokenType.CLOSE_ARRAY, readImpl)
    }

    fun <R> push(
        i: Int, open: TokenType, close: TokenType,
        readImpl: () -> R
    ): R {
        val end = findBlockEnd(i, open, close)
        return push(end, readImpl)
    }

    fun findBlockEnd(i: Int, open: TokenType, close: TokenType): Int {
        assert(equals(i, open))
        var depth = 1
        var j = i + 1
        while (depth > 0) {
            when (getType(j++)) {
                open, TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                close, TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> depth--
                else -> {}
            }
        }
        return j - 1
    }

    fun <R> push(j: Int, readImpl: () -> R): R {
        val oldSize = size
        size = j
        val result = readImpl()
        size = oldSize
        return result
    }

    fun <R> push(
        i: Int, open: TokenType, openStr: String, close: TokenType, closeStr: String,
        readImpl: () -> R
    ): R {
        assert(equals(i, openStr))
        var depth = 1
        var j = i
        while (depth > 0) {
            j++
            when {
                equals(j, openStr) -> depth++
                equals(j, closeStr) -> depth--
            }
        }
        val oldSize = size
        size = j
        val result = readImpl()
        size = oldSize
        return result
    }

    fun getType(i: Int): TokenType {
        if (i >= size) throw IndexOutOfBoundsException("$i >= $size at ${err(size - 1)}")
        return TokenType.entries[tokenTypes[i].toInt()]
    }

    fun setType(i: Int, keyword: TokenType) {
        tokenTypes[i] = keyword.ordinal.toByte()
    }

    fun err(i: Int): String {
        val before = src.substring(0, getI0(i))
        val lineNumber = before.count { it == '\n' } + 1
        val lastLineBreak = before.lastIndexOf('\n')
        val pos0 = getI0(i) - lastLineBreak
        val pos1 = getI1(i) - lastLineBreak
        return "$fileName:$lineNumber, ${pos0}-${pos1}, ${getType(i)}, '${toString(i)}'"
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
            i0 == offsets[size * 2 - 1] &&
            src[i0] != ';' &&
            src[i0 - 1] != ';' &&
            (src[i0 - 1] != '=' || src[i0] == '=')
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

    fun equals(i: Int, type: TokenType): Boolean = getType(i) == type
    fun equals(i: Int, str: String): Boolean {
        if (equals(i, TokenType.STRING)) return false
        val i0 = getI0(i)
        val i1 = getI1(i)
        if (i1 - i0 != str.length) return false
        return str.indices.all { strIndex ->
            str[strIndex] == src[i0 + strIndex]
        }
    }

    override fun toString(): String {
        return (0 until size).map { i ->
            "${getType(i)}(${toString(i)})"
        }.toString()
    }

    fun toString(i: Int): String {
        if (i >= size) throw IndexOutOfBoundsException("$i >= $size, ${TokenType.entries[tokenTypes[i].toInt()]}")
        return src.substring(getI0(i), getI1(i))
    }

    fun isSameLine(tokenI: Int, tokenJ: Int): Boolean {
        val i0 = getI0(tokenI)
        val i1 = getI1(tokenJ)
        for (i in i0 until i1) {
            if (src[i] == '\n') return false
        }
        return true
    }

    fun removeLast() {
        size--
    }

    fun findToken(i0: Int, type: TokenType, str: String): Int {
        var depth = 0
        for (i in i0 until size) {
            when {
                depth == 0 && equals(i, str) -> return i
                equals(i, TokenType.OPEN_BLOCK) ||
                        equals(i, TokenType.OPEN_ARRAY) ||
                        equals(i, TokenType.OPEN_CALL) -> depth++
                equals(i, TokenType.CLOSE_BLOCK) ||
                        equals(i, TokenType.CLOSE_ARRAY) ||
                        equals(i, TokenType.CLOSE_CALL) -> depth--
            }
        }
        return -1
    }

}