package me.anno.zauberei.astbuilder

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder.Companion.fileLevelKeywords
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.Import
import me.anno.zauberei.types.SuperCallName

object ASTClassScanner {

    /**
     * to make type-resolution immediately available/resolvable
     * */
    fun findNamedClasses(tokens: TokenList) {

        var depth = 0
        var listen = -1
        var listenType = ""

        var currPackage = root
        var nextPackage = root

        val imports = ArrayList<Import>()
        val listening = ArrayList<Boolean>()
        listening.add(true)

        for (i in 0 until tokens.size) {
            when (tokens.getType(i)) {
                TokenType.OPEN_BLOCK -> {

                    if (listenType == "body?") {
                        listening.add(true)
                        currPackage = nextPackage
                    } else {
                        depth++
                        listening.add(false)
                    }

                    listen = -1
                    listenType = ""
                }
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY -> depth--
                TokenType.CLOSE_BLOCK -> {
                    depth--
                    if (listening.removeLast()) {
                        currPackage = currPackage.parent ?: root
                    } else depth--
                }
                else ->
                    if (depth == 0) {
                        when {

                            tokens.equals(i, "package") && listening.size == 1 -> {
                                var j = i + 1
                                assert(tokens.equals(j, TokenType.NAME))
                                var path = root.getOrPut(tokens.toString(j++))
                                while (tokens.equals(j, ".") && tokens.equals(j + 1, TokenType.NAME)) {
                                    path = path.getOrPut(tokens.toString(j + 1))
                                    j += 2 // skip period and name
                                }
                                currPackage = path
                                currPackage.fileName = tokens.fileName
                            }

                            tokens.equals(i, "import") && listening.size == 1 -> {
                                var j = i + 1
                                assert(tokens.equals(j, TokenType.NAME))
                                var path = root.getOrPut(tokens.toString(j++))
                                while (tokens.equals(j, ".") && tokens.equals(j + 1, TokenType.NAME)) {
                                    path = path.getOrPut(tokens.toString(j + 1))
                                    j += 2 // skip period and name
                                }
                                val allChildren = tokens.equals(j, ".*")
                                imports.add(Import(path, allChildren))
                            }

                            // tokens.equals(i, "<") -> if (listen >= 0) genericsDepth++
                            // tokens.equals(i, ">") -> if (listen >= 0) genericsDepth--

                            tokens.equals(i, "var") || tokens.equals(i, "val") || tokens.equals(i, "fun") -> {
                                listen = -1
                                listenType = ""
                            }

                            tokens.equals(i, "class") && !tokens.equals(i - 1, "::") && listening.last() -> {
                                listen = i
                                listenType = "class"
                            }
                            tokens.equals(i, "object") && listening.last() -> {
                                listen = i
                                listenType = "object"
                            }
                            tokens.equals(i, "interface") && listening.last() -> {
                                listen = i
                                listenType = "interface"
                            }

                            listen >= 0 && tokens.equals(i, TokenType.NAME) &&
                                    fileLevelKeywords.none { keyword -> tokens.equals(i, keyword) } -> {

                                nextPackage = currPackage.getOrPut(tokens.toString(i))
                                nextPackage.keywords.add(listenType)

                                println("discovered $nextPackage")

                                var j = i + 1 // after name
                                if (tokens.equals(j, "<")) {
                                    // skip generic type parameters
                                    j = tokens.findBlockEnd(j, "<", ">") + 1
                                }
                                if (tokens.equals(j, "private")) j++
                                if (tokens.equals(j, "protected")) j++
                                if (tokens.equals(j, "constructor")) j++
                                if (tokens.equals(j, TokenType.OPEN_CALL)) {
                                    // skip constructor params
                                    j = tokens.findBlockEnd(j, TokenType.OPEN_CALL, TokenType.CLOSE_CALL) + 1
                                }
                                if (tokens.equals(j, ":")) {
                                    j++
                                    while (tokens.equals(j, TokenType.NAME)) {
                                        val name = tokens.toString(j++)
                                        // println("discovered $nextPackage extends $name")
                                        nextPackage.superCallNames.add(SuperCallName(name, imports))
                                        if (tokens.equals(j, "<")) {
                                            j = tokens.findBlockEnd(j, "<", ">") + 1
                                        }
                                        if (tokens.equals(j, TokenType.OPEN_CALL)) {
                                            j = tokens.findBlockEnd(j, TokenType.OPEN_CALL, TokenType.CLOSE_CALL) + 1
                                        }
                                        if (tokens.equals(j, TokenType.COMMA)) j++
                                        else break
                                    }
                                }

                                listen = -1
                                listenType = "body?"
                            }
                        }
                    }
            }
        }
        assert(listen == -1) { "Listening for class/object/interface at ${tokens.err(listen)}" }

        //if (debug) throw IllegalStateException()
    }
}