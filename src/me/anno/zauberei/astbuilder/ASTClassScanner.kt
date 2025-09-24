package me.anno.zauberei.astbuilder

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder.Companion.debug
import me.anno.zauberei.astbuilder.ASTBuilder.Companion.fileLevelKeywords
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType

object ASTClassScanner {
    /**
     * to make type-resolution immediately available/resolvable
     * */
    fun discoverClasses(tokens: TokenList) {
        var depth = 0
        var listen = -1
        var listenType = ""
        var typeDepth = 0
        var currPackage = root
        for (i in 0 until tokens.size) {
            when (tokens.getType(i)) {
                TokenType.OPEN_BLOCK -> {
                    depth++
                    listen = -1
                }
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_BLOCK, TokenType.CLOSE_ARRAY -> depth--
                else -> {
                    if (depth == 0) {
                        when {
                            tokens.equals(i, "package") -> {
                                var j = i + 1
                                assert(tokens.equals(j, TokenType.NAME))
                                var path = root.getOrPut(tokens.toString(j++))
                                while (tokens.equals(j, ".") && tokens.equals(j + 1, TokenType.NAME)) {
                                    path = path.getOrPut(tokens.toString(j + 1))
                                    j += 2 // skip period and name
                                }
                                currPackage = path
                            }
                            tokens.equals(i, "<") -> if (listen >= 0) typeDepth++
                            tokens.equals(i, ">") -> if (listen >= 0) typeDepth--
                            // tokens.equals(i, "var") || tokens.equals(i, "val") ||
                            // tokens.equals(i, "fun") ||
                            tokens.equals(i, "class") && !tokens.equals(i - 1, "::") -> {
                                listen = i
                                listenType = "class"
                            }
                            tokens.equals(i, "object") -> {
                                listen = i
                                listenType = "object"
                            }
                            tokens.equals(i, "interface") -> {
                                listen = i
                                listenType = "interface"
                            }
                            typeDepth == 0 && tokens.equals(i, TokenType.NAME) && listen >= 0 &&
                                    fileLevelKeywords.none { keyword -> tokens.equals(i, keyword) } -> {
                                currPackage.getOrPut(tokens.toString(i)).keywords.add(listenType)
                                if (debug) println("found ${tokens.toString(i)} in $currPackage")
                                listen = -1
                                listenType = ""
                            }
                        }
                    }
                }
            }
        }
        assert(listen == -1) { "Listening for class/object/interface at ${tokens.err(listen)}" }
    }
}