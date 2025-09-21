package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Package
import me.anno.zauberei.types.Type

class ASTBuilder(val tokens: TokenList, val root: Package) {

    companion object {
        private val fileLevelKeywords = listOf(
            "enum", "private", "fun", "class", "data",
            "companion", "object", "constructor"
        )
        private val paramLevelKeywords = listOf(
            "private", "var", "val"
        )
    }

    val imports = ArrayList<Package>()
    val keywords = ArrayList<String>()
    val names = ArrayList<String>()

    var currPackage = root
    var i = 0
    var n = tokens.size

    fun readPath(): Package {
        assert(tokens.equals(i, TokenType.NAME))
        var path = root.getOrPut(tokens.getString(i++))
        while (tokens.equals(i, TokenType.SYMBOL, ".")) {
            assert(tokens.equals(++i, TokenType.NAME))
            path = path.getOrPut(tokens.getString(i++))
        }
        return path
    }

    fun readFileLevel() {
        // todo parse until } or end
        // todo depending on context, accept different keywords

        loop@ while (i < n) {
            when {
                tokens.equals(i, TokenType.NAME, "package") -> {
                    i++; currPackage = readPath()
                }
                tokens.equals(i, TokenType.NAME, "import") -> {
                    i++; imports.add(readPath())
                }
                tokens.equals(i, TokenType.NAME) -> collectNames(fileLevelKeywords)
                tokens.equals(i, TokenType.OPEN_CALL) -> {
                    i++
                    val isClass = keywords.remove("class")
                    val isFun = keywords.remove("fun")
                    val isObject = keywords.remove("object")
                    assert(isClass.toInt() + isFun.toInt() + isObject.toInt() == 1)
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeFirst()
                    when {
                        isClass -> {
                            currPackage = currPackage.getOrPut(name)
                            currPackage.keywords.addAll(keywords); keywords.clear() // safe keywords
                            readParameters()
                            if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                                i++
                                readFileLevel()
                            }
                            currPackage = currPackage.parent!!
                        }
                        isFun -> {
                            val keywords = packKeywords()
                            readParameters()
                            TODO("read function")
                        }
                        isObject -> throw IllegalStateException("Objects cannot have parameters")
                        else -> throw IllegalStateException("Cannot happen")
                    }
                }
                tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                    i++
                    val isInit = keywords.remove("init")
                    val isCompanion = keywords.remove("object")
                    assert(isInit.toInt() + isCompanion.toInt() == 1)
                    assert(names.isEmpty())
                    when {
                        isInit -> {
                            val body = readFunctionBody()
                            currPackage.initialization += body.members
                        }
                        isCompanion -> {
                            assert("companion" in keywords)
                            val name = "Companion"
                            currPackage = currPackage.getOrPut(name)
                            currPackage.keywords.addAll(keywords); keywords.clear() // safe keywords
                            readFileLevel()
                            currPackage = currPackage.parent!!
                        }
                        else -> throw IllegalStateException("Cannot happen")
                    }
                }
                tokens.equals(i, TokenType.SYMBOL, "=") -> {
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(isVar.toInt() + isVal.toInt() == 1) { "Expected either val or var" }
                    val keywords = packKeywords()
                    val initialValue = readExpression()
                    val type: Type? = null // unknown in this case
                    val field = Field(isVar, isVal, name, type, initialValue, keywords)
                    currPackage.fields.add(field)
                }
                else -> throw NotImplementedError(
                    "Unknown token ${tokens.getType(i)}, ${tokens.getString(i)} in " +
                            currPackage.path.joinToString(".")
                )
            }
        }
    }

    fun packKeywords(): List<String> {
        val tmp = ArrayList(keywords)
        keywords.clear()
        return tmp
    }

    fun collectNames(keywords1: List<String>) {
        for (keyword in keywords1) {
            if (tokens.equals(i, keyword)) {
                keywords.add(keyword)
                i++
                return
            }
        }

        names.add(tokens.getString(i++))
    }

    fun readParameters(): List<Parameter> {
        val result = ArrayList<Parameter>()
        while (i < n) {
            println("param[$i]: ${tokens.getString(i)}")
            when {
                tokens.equals(i, TokenType.NAME) -> collectNames(paramLevelKeywords)
                tokens.equals(i, TokenType.SYMBOL, ":") -> {
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    i++
                    val type = readType()
                    println("type: $type")
                    val initialValue = if (tokens.equals(i, TokenType.SYMBOL, "=")) {
                        i++
                        readExpression()
                    } else null
                    result.add(Parameter(isVar, isVal, name, type, initialValue))
                    keywords.clear()

                    when {
                        tokens.equals(i, TokenType.COMMA) -> i++
                        tokens.equals(i, TokenType.CLOSE_CALL) -> {
                            i++
                            return result
                        }
                        else -> throw IllegalStateException("Unknown token in params")
                    }
                }
                // todo read annotations
                else -> throw NotImplementedError("Unknown token ${tokens.getType(i)}, ${tokens.getString(i)}")
            }
        }
        return result
    }

    fun readType(): Type {
        val path = readPath()
        if (tokens.equals(i, TokenType.SYMBOL, "<")) TODO("read generics")
        return ClassType(path)
    }

    fun readExpression(): Expression {
        // todo this may become complicated...
        //  if ending with a symbol, continue
        //  count brackets
        //  order operations by their symbol...
        //  ...
        TODO("read expression")
    }

    fun readFunctionBody(): ExpressionList {
        val result = ArrayList<Expression>()
        while (true) {
            if (tokens.equals(i, TokenType.CLOSE_BLOCK)) {
                i++
                return ExpressionList(result)
            } else if (tokens.equals(i, TokenType.SYMBOL, ";")) {
                i++
            }

            result.add(readExpression())
        }
    }

    fun Boolean.toInt() = if (this) 1 else 0
}