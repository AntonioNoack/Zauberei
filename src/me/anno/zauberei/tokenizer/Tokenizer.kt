package me.anno.zauberei.tokenizer

class Tokenizer(val src: String, fileName: String) {

    companion object {
        private val hardKeywords = listOf(
            "true", "false", "null",
            "class", "interface", "object", "package",
            "val", "var", "fun",

            "if", "else", "do", "while", "when", "for",
            "return", "break", "throw", "continue",
            "in", "!in", "is", "!is", "as", "as?",

            "super", "this",
            "try", "typealias", "typeof",
        ).toSet()
    }

    var i = 0
    var n = src.length
    val tokens = TokenList(src, fileName)

    fun skipSingleLineComment() {
        i += 2
        while (i < n && src[i] != '\n') i++
    }

    fun skipBlockComment() {
        i += 2
        while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
        i += 2
    }

    fun tokenize(): TokenList {
        while (i < n) {
            val c = src[i]
            when {
                // c == '\n' -> list.add(TokenType.LINE_BREAK, i++, i)
                c.isWhitespace() -> i++ // skip spaces

                // comments
                c == '/' && i + 1 < n && src[i + 1] == '/' -> skipSingleLineComment()
                c == '/' && i + 1 < n && src[i + 1] == '*' -> skipBlockComment()

                // identifiers
                c.isLetter() || c == '_' -> {
                    val start = i
                    i++
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    if (i < n && src[i] == '@') {
                        tokens.add(TokenType.LABEL, start, i)
                        i++
                    } else tokens.add(TokenType.NAME, start, i)
                }

                // numbers
                c.isDigit() -> readNumber()

                // char literal = number
                c == '\'' -> {
                    val start = i++
                    if (i < n && src[i] == '\\') i += 2
                    while (i < n && src[i] != '\'') i++
                    if (i < n) i++ // skip \'
                    tokens.add(TokenType.NUMBER, start, i)
                }

                // string with interpolation
                c == '"' -> parseString()

                // special one-char tokens
                c == ',' -> tokens.add(TokenType.COMMA, i++, i)
                c == '(' -> tokens.add(TokenType.OPEN_CALL, i++, i)
                c == ')' -> tokens.add(TokenType.CLOSE_CALL, i++, i)
                c == '{' -> tokens.add(TokenType.OPEN_BLOCK, i++, i)
                c == '}' -> tokens.add(TokenType.CLOSE_BLOCK, i++, i)
                c == '[' -> tokens.add(TokenType.OPEN_ARRAY, i++, i)
                c == ']' -> tokens.add(TokenType.CLOSE_ARRAY, i++, i)

                c == '?' -> {
                    // parse 'as?'
                    if (tokens.size > 0 && tokens.equals(tokens.size - 1, "as")) {
                        val i0 = tokens.getI0(tokens.size - 1)
                        tokens.removeLast()
                        tokens.add(TokenType.SYMBOL, i0, ++i)
                    } else tokens.add(TokenType.SYMBOL, i++, i)
                }

                c == '!' -> {
                    // parse !in and !is
                    if (i + 3 < src.length && src[i + 1] == 'i' && src[i + 2] in "sn" && src[i + 3].isWhitespace()) {
                        tokens.add(TokenType.SYMBOL, i, i + 3)
                        i += 3
                    } else tokens.add(TokenType.SYMBOL, i++, i)
                }

                c == '.' -> {
                    // parse !in and !is
                    if (i + 1 < src.length && src[i + 1].isDigit()) {
                        readNumber()
                    } else tokens.add(TokenType.SYMBOL, i++, i)
                }

                // symbols
                else -> tokens.add(TokenType.SYMBOL, i++, i)
            }
        }
        convertHardKeywords()
        return tokens
    }

    private fun readNumber() {
        val start = i
        i++
        // todo support hH for half fp :)
        while (i < n && (src[i].isDigit() || src[i] in ".eE+-lLuUfFdDhH_xabcdefABCDEF")) {
            if (i + 1 < n && src[i] == '.' && src[i + 1] == '.') break // .. operator
            i++
        }
        tokens.add(TokenType.NUMBER, start, i)
    }

    private fun convertHardKeywords() {
        for (i in 0 until tokens.size) {
            if (tokens.getType(i) == TokenType.NAME) {
                val asString = tokens.toString(i)
                if (asString in hardKeywords) {
                    tokens.setType(i, TokenType.KEYWORD)
                }
            }
        }
    }

    private fun parseString() {
        val open = i
        i++ // skip initial "
        tokens.add(TokenType.OPEN_CALL, open, open + 1)

        var chunkStart = i
        fun flushChunk(until: Int) {
            tokens.add(TokenType.STRING, chunkStart, until)
        }

        while (i < n) {
            when (src[i]) {
                '\\' -> i += 2 // skip escaped char
                '"' -> {
                    flushChunk(i)

                    i++ // skip closing "
                    tokens.add(TokenType.CLOSE_CALL, i - 1, i)
                    return
                }
                '$' -> {
                    flushChunk(i)

                    // Begin: + ( ... )
                    tokens.add(TokenType.APPEND_STRING, i, i + 1)
                    tokens.add(TokenType.OPEN_CALL, i, i)

                    i++ // consume $
                    if (i < n && src[i] == '{') {
                        // ${ expr }
                        i++ // skip {
                        val innerStart = i
                        skipBlock()
                        val innerEnd = i - 1

                        val oldI = i
                        val oldN = n
                        i = innerStart
                        n = innerEnd

                        // tokenize substring recursively
                        tokenize()

                        i = oldI
                        n = oldN

                    } else {
                        // $name
                        val start = i
                        i++
                        while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                        tokens.add(TokenType.NAME, start, i)
                    }

                    // End: )
                    tokens.add(TokenType.CLOSE_CALL, i, i)
                    tokens.add(TokenType.APPEND_STRING, i, i)

                    chunkStart = i
                }
                else -> i++
            }
        }
    }

    fun skipBlock() {
        assert(src[i - 1] == '{')
        var depth = 1
        loop@ while (i < n && depth > 0) {
            if (src[i] in "([{") depth++
            else if (src[i] in ")]}") depth--
            else if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '/') {
                skipSingleLineComment()
            } else if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '*') {
                skipBlockComment()
            } else if (src[i] == '"') {
                // skip string
                val size = tokens.size
                parseString()
                tokens.size = size
                continue@loop // must not call i++
            }
            i++
        }
        assert(src[i - 1] == '{')
    }
}