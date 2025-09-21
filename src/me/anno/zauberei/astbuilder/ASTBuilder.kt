package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ListExpression
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.ClassType
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
        assert(tokens.equals(++i, TokenType.NAME))
        var path = root.getOrPut(tokens.getString(i++))
        while (tokens.equals(i, TokenType.SYMBOL, ".")) {
            assert(tokens.equals(++i, TokenType.NAME))
            path = path.getOrPut(tokens.getString(i++))
        }
        return path
    }

    fun readFileLevel(): Pair<Expression, Int> {
        // todo parse until } or end
        // todo depending on context, accept different keywords

        loop@ while (i < n) {
            when {
                tokens.equals(i, TokenType.NAME, "package") -> currPackage = readPath()
                tokens.equals(i, TokenType.NAME, "import") -> imports.add(readPath())
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
                else -> throw NotImplementedError("Unknown token ${tokens.getType(i)}, ${tokens.getString(i)}")
            }
        }
        return ListExpression(emptyList()) to -1
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
            when {
                tokens.equals(i, TokenType.NAME) -> collectNames(paramLevelKeywords)
                tokens.equals(i, TokenType.SYMBOL, ":") -> {
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    i++
                    val type = readType()
                    val initialValue = if (tokens.equals(i, TokenType.SYMBOL, "=")) {
                        i++
                        readExpression()
                    } else null
                    result.add(Parameter(isVar, isVal, name, type, initialValue))
                    keywords.clear()
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
        TODO("")
    }

    fun Boolean.toInt() = if (this) 1 else 0
}