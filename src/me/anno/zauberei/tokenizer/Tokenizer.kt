package me.anno.zauberei.tokenizer

class Tokenizer(val src: String) {

    var i = 0
    var n = src.length
    val list = TokenList(src)

    fun tokenize(): TokenList {
        while (i < n) {
            val c = src[i]
            when {
                // c == '\n' -> list.add(TokenType.LINE_BREAK, i++, i)
                c.isWhitespace() -> i++ // skip spaces

                // comments
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }

                // identifiers / strings
                c.isLetter() || c == '_' -> {
                    val start = i
                    i++
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    list.add(TokenType.NAME, start, i)
                }

                // numbers
                c.isDigit() -> {
                    val start = i
                    i++
                    while (i < n && (src[i].isDigit() || src[i] in ".eE+-")) i++
                    list.add(TokenType.NUMBER, start, i)
                }

                // char literal = number
                c == '\'' -> {
                    val start = i
                    i++
                    if (i < n && src[i] != '\'') i++
                    if (i < n && src[i] == '\'') i++
                    list.add(TokenType.NUMBER, start, i)
                }

                // string with interpolation
                c == '"' -> parseString()

                // special one-char tokens
                c == ',' -> list.add(TokenType.COMMA, i++, i)
                c == '(' -> list.add(TokenType.OPEN_CALL, i++, i)
                c == ')' -> list.add(TokenType.CLOSE_CALL, i++, i)
                c == '{' -> list.add(TokenType.OPEN_BLOCK, i++, i)
                c == '}' -> list.add(TokenType.CLOSE_BLOCK, i++, i)
                c == '[' -> list.add(TokenType.OPEN_ARRAY, i++, i)
                c == ']' -> list.add(TokenType.CLOSE_ARRAY, i++, i)

                // symbols
                else -> {
                    val start = i
                    i++
                    list.add(TokenType.SYMBOL, start, i)
                }
            }
        }
        return list
    }

    private fun parseString() {
        val open = i
        i++ // skip initial "
        list.add(TokenType.OPEN_CALL, open, open + 1)

        var chunkStart = i
        fun flushChunk(until: Int) {
            if (until > chunkStart) {
                list.add(TokenType.STRING, chunkStart, until)
            }
        }

        while (i < n) {
            when (val ch = src[i]) {
                '\\' -> i += 2 // skip escaped char

                '"' -> {
                    flushChunk(i)
                    i++ // skip closing "
                    list.add(TokenType.CLOSE_CALL, i - 1, i)
                    return
                }

                '$' -> {
                    flushChunk(i)

                    // Begin: + ( ... )
                    list.add(TokenType.PLUS, i, i + 1)
                    list.add(TokenType.OPEN_CALL, i, i)

                    i++ // consume $
                    if (i < n && src[i] == '{') {
                        // ${ expr }
                        i++ // skip {
                        val innerStart = i
                        var depth = 1
                        while (i < n && depth > 0) {
                            if (src[i] == '{') depth++
                            else if (src[i] == '}') depth--
                            i++
                        }
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
                        list.add(TokenType.NAME, start, i)
                    }

                    // End: )
                    list.add(TokenType.CLOSE_CALL, i, i)
                    list.add(TokenType.PLUS, i, i)

                    chunkStart = i
                }

                else -> i++
            }
        }
    }
}