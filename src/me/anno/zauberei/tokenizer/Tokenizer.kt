package me.anno.zauberei.tokenizer

class Tokenizer(val src: String, fileName: String) {

    var i = 0
    var n = src.length
    val tokens = TokenList(src, fileName)

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
                c.isDigit() -> {
                    val start = i
                    i++
                    while (i < n && (src[i].isDigit() || src[i] in ".eE+-")) i++
                    tokens.add(TokenType.NUMBER, start, i)
                }

                // char literal = number
                c == '\'' -> {
                    val start = i
                    i++
                    if (i < n && src[i] != '\'') i++
                    if (i < n && src[i] == '\'') i++
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

                // symbols
                else -> {
                    val start = i
                    i++
                    tokens.add(TokenType.SYMBOL, start, i)
                }
            }
        }
        return tokens
    }

    private fun parseString() {
        val open = i
        i++ // skip initial "
        tokens.add(TokenType.OPEN_CALL, open, open + 1)

        var chunkStart = i
        fun flushChunk(until: Int) {
            if (until > chunkStart) {
                tokens.add(TokenType.STRING, chunkStart, until)
            }
        }

        while (i < n) {
            when (src[i]) {
                '\\' -> i += 2 // skip escaped char
                '"' -> {
                    flushChunk(i)

                    if (tokens.size > 0 && tokens.equals(tokens.size - 1, TokenType.APPEND_STRING)) {
                        tokens.removeLast()
                    }

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
}