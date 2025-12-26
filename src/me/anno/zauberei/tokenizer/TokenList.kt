package me.anno.zauberei.tokenizer

import kotlin.math.max

// could be placed into a token list...
class TokenList(val src: String, val fileName: String) {

    var size = 0
    var tliIndex = -1

    private var tokenTypes = ByteArray(16)
    private var offsets = IntArray(32)
    val totalSize get() = tokenTypes.size

    inline fun <R> push(
        i: Int, open: TokenType, close: TokenType,
        readImpl: () -> R
    ): R = push(findBlockEnd(i, open, close), readImpl)

    inline fun <R> push(j: Int, readImpl: () -> R): R {
        val oldSize = size
        size = j
        val result = readImpl()
        size = oldSize
        return result
    }

    fun <R> push(
        i: Int, openStr: String, closeStr: String,
        readImpl: () -> R
    ): R = push(findBlockEnd(i, openStr, closeStr), readImpl)

    fun findBlockEnd(i: Int, open: TokenType, close: TokenType): Int {
       check(equals(i, open))
        var depth = 1
        var j = i + 1
        while (depth > 0) {
            if (j >= size) {
                printTokensInBlocks(i, open, close)
                throw IllegalStateException("Could not find block end for $open/$close at ${err(i)}, #${size - i}")
            }
            when (getType(j++)) {
                open, TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                close, TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> depth--
                else -> {}
            }
        }
        return j - 1
    }

    fun printTokensInBlocks(i: Int) {
        printTokensInBlocks(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
    }

    fun printTokensInBlocks(i: Int, open: TokenType, close: TokenType) {
        var depth = 0
        for (j in i until size) {
            when (getType(j)) {
                close, TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> depth--
                else -> {}
            }
            if (depth < 0) break
            println("  ".repeat(depth) + "$j: ${getType(j)} '${toString(j)}'")
            when (getType(j)) {
                open, TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                else -> {}
            }
        }
    }

    fun findBlockEnd(i: Int, open: String, close: String): Int {
        check(equals(i, open))
        var depth = 1
        var j = i + 1
        while (depth > 0) {
            if (equals(j, open)) depth++
            else if (equals(j, close)) depth--
            else when (getType(j)) {
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> depth--
                else -> {}
            }
            j++
        }
        return j - 1
    }

    fun getType(i: Int): TokenType {
        if (i >= size) throw IndexOutOfBoundsException("$i >= $size at ${err(size - 1)}")
        return TokenType.entries[tokenTypes[i].toInt()]
    }

    fun getTypeUnsafe(i: Int): TokenType {
        return TokenType.entries[tokenTypes[i].toInt()]
    }

    fun setType(i: Int, keyword: TokenType) {
        tokenTypes[i] = keyword.ordinal.toByte()
    }

    fun err(i: Int): String {
        val i = max(i, 0)
        val before = src.substring(0, getI0(i))
        val lineNumber = before.count { it == '\n' } + 1
        val lastLineBreak = before.lastIndexOf('\n')
        val pos0 = getI0(i) - lastLineBreak
        val pos1 = getI1(i) - lastLineBreak
        return "$fileName:$lineNumber, ${pos0}-${pos1}, ${getTypeUnsafe(i)}, '${toStringUnsafe(i)}'"
    }

    fun getI0(i: Int) = offsets[i * 2]
    fun getI1(i: Int) = offsets[i * 2 + 1]

    fun add(type: TokenType, i0: Int, i1: Int) {
        if (size == tokenTypes.size) {
            tokenTypes = tokenTypes.copyOf(size * 2)
            offsets = offsets.copyOf(size * 4)
        }

        if (i0 > i1) throw IllegalStateException("i0 > i1, $i0 > $i1 in $fileName")
        if (i1 > src.length) throw IllegalStateException("i1 > src.len, $i1 > ${src.length} in $fileName")

        if (size > 0 && type == TokenType.SYMBOL &&
            getType(size - 1) == TokenType.SYMBOL &&
            i0 == offsets[size * 2 - 1] &&
            src[i0] != ';' &&
            src[i0 - 1] != ';' &&
            (src[i0] != '>' || src[i0 - 1] == '-') && // ?>
            (src[i0 - 1] != '>' || src[i0] == '=') && // >?, >>, >>., but allow >=
            !(src[i0 - 1] == '<' && src[i0] == '*') && // <*>
            !(src[i0 - 1] == '*' && src[i0] == '>') && // <*>
            !(src[i0 - 1] == '!' && src[i0] in ":.") && // !!::, !!.
            !(src[i0 - 1] == '.' && src[i0] in "+-") && // ..+3.0, ..-3.0
            !(src[i0 - 1] in "&|" && src[i0] == '!') && // &!, |!
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

    fun equals(i: Int, type: TokenType): Boolean {
        return i in 0 until size && getType(i) == type
    }

    fun equals(i: Int, str: String): Boolean {
        if (i !in 0 until size) return false
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

    fun endsWith(i: Int, ch: Char): Boolean {
        return src[getI1(i) - 1] == ch
    }

    fun toString(i0: Int, i1: Int): String {
        return (i0 until i1).joinToString(", ") { i ->
            "${getType(i)},'${toString(i)}'"
        }
    }

    fun toStringUnsafe(i: Int): String {
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

    fun findToken(i0: Int, str: String): Int {
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

    fun findToken(i0: Int, type: TokenType): Int {
        var depth = 0
        for (i in i0 until size) {
            when {
                depth == 0 && equals(i, type) -> return i
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