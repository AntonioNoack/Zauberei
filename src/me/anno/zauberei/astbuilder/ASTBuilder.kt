package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.flow.DoWhileLoop
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.WhileLoop
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
        private val expressionLevelKeywords = listOf(
            "if", "else", "while", "do", "for", "when"
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
        var path = root.getOrPut(tokens.toString(i++))
        while (tokens.equals(i, TokenType.SYMBOL, ".")) {
            i++
            assert(tokens.equals(i, TokenType.NAME))
            path = path.getOrPut(tokens.toString(i++))
        }
        if (tokens.equals(i, TokenType.SYMBOL, ".*")) {
            i++
            path = path.getOrPut("*")
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
                    i++
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(isVar.toInt() + isVal.toInt() == 1) { "Expected either val or var" }
                    val keywords = packKeywords()
                    val initialValue = readExpression()
                    val type: Type? = null // unknown in this case
                    val field = Field(isVar, isVal, name, type, initialValue, keywords)
                    println("read field $name: $type = $initialValue")
                    currPackage.fields.add(field)
                }
                /*tokens.equals(i, TokenType.LINE_BREAK) -> {
                    i++
                }*/
                tokens.equals(i, TokenType.CLOSE_BLOCK) -> {
                    i++
                    return // finished :)
                }
                else -> throw NotImplementedError(
                    "Unknown token ${tokens.getType(i)}, ${tokens.toString(i)} in " +
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

        names.add(tokens.toString(i++))
    }

    fun readParameters(): List<Parameter> {
        val result = ArrayList<Parameter>()
        while (i < n) {
            println("param[$i]: ${tokens.toString(i)}")
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
                else -> throw NotImplementedError("Unknown token ${tokens.getType(i)}, ${tokens.toString(i)}")
            }
        }
        return result
    }

    fun readType(): Type {
        val path = readPath()
        if (tokens.equals(i, TokenType.SYMBOL, "<")) TODO("read generics")
        return ClassType(path)
    }

    fun readExpressionCondition(): Expression {
        assert(tokens.equals(i++, TokenType.OPEN_CALL))
        val expr = readExpression()
        assert(tokens.equals(i++, TokenType.CLOSE_CALL))
        return expr
    }

    fun readBodyOrLine(): Expression {
        return if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            i++
            readFunctionBody()
        } else {
            readExpression()
        }
    }

    fun readExpressionPart(): Expression {
        when {
            tokens.equals(i, TokenType.NUMBER) -> return NumberExpression(tokens.toString(i++))
            tokens.equals(i, TokenType.STRING) -> return StringExpression(tokens.toString(i++))
            tokens.equals(i, TokenType.NAME, "if") -> {
                i++
                val condition = readExpressionCondition()
                val ifTrue = readBodyOrLine()
                val ifFalse = if (tokens.equals(i, TokenType.NAME, "else")) {
                    i++
                    readBodyOrLine()
                } else ExpressionList.empty
                return IfElseBranch(condition, ifTrue, ifFalse)
            }
            tokens.equals(i, TokenType.NAME, "else") -> throw IllegalStateException("Unexpected else")
            tokens.equals(i, TokenType.NAME, "while") -> {
                i++
                val condition = readExpressionCondition()
                val body = readBodyOrLine()
                return WhileLoop(condition, body)
            }
            tokens.equals(i, TokenType.NAME, "do") -> {
                i++
                val body = readBodyOrLine()
                val condition = readExpressionCondition()
                return DoWhileLoop(body, condition)
            }
            tokens.equals(i, TokenType.NAME, "when") -> {
                i++
                if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    TODO("read declaration, assignment or name")
                } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    TODO("read boolean expressions, arrows, and their following blocks")
                } else throw IllegalStateException(
                    "Unexpected token after when ${tokens.getType(i)}, ${tokens.toString(i)}"
                )
            }
            tokens.equals(i, TokenType.NAME) -> {
                val variableName = tokens.toString(i++)
               /* if (tokens.startsWith(i, "<")) {

                }*/
                TODO()
                return VariableExpression(variableName)
            }
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                // just something in brackets
                i++
                val inside = readExpressionPart()
                assert(tokens.equals(i, TokenType.CLOSE_CALL))
                i++
                return inside
            }
            else -> throw NotImplementedError("Unknown expression part ${tokens.getType(i)}, ${tokens.toString(i)}")
        }
    }

    fun readExpression(): Expression {
        val chain = ArrayList<Expression>()
        val symbols = ArrayList<String>()
        chain.add(readExpressionPart())

        loop@ while (true) {
            when {
                tokens.equals(i, TokenType.SYMBOL) -> {
                    symbols.add(tokens.toString(i++))
                    when {
                        tokens.equals(i, TokenType.SYMBOL, "-") -> {
                            i++
                            chain.add(UnaryOp("-", readExpressionPart()))
                        }
                        tokens.equals(i, TokenType.SYMBOL, "!") -> {
                            i++
                            chain.add(UnaryOp("!", readExpressionPart()))
                        }
                        else -> chain.add(readExpressionPart())
                    }
                }
                tokens.equals(i, TokenType.OPEN_CALL) -> {
                    i++
                    val params = ArrayList<Expression>()
                    params@ while (true) {
                        params.add(readExpression())
                        if (tokens.equals(i, TokenType.COMMA)) {
                            i++
                        } else if (tokens.equals(i, TokenType.CLOSE_CALL)) {
                            i++
                            break@params
                        } else throw IllegalStateException("Expected comma or closing bracket")
                    }
                    if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                        i++
                        params.add(readLambda())
                    }
                    chain[chain.lastIndex] = CallExpression(chain.last(), params)
                }
                tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                    i++
                    val params = listOf(readLambda())
                    chain[chain.lastIndex] = CallExpression(chain.last(), params)
                }
                tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                    i++
                    val params = ArrayList<Expression>()
                    params@ while (true) {
                        params.add(readExpression())
                        if (tokens.equals(i, TokenType.COMMA)) {
                            i++
                        } else if (tokens.equals(i, TokenType.CLOSE_ARRAY)) {
                            i++
                            break@params
                        } else throw IllegalStateException("Expected comma or closing bracket")
                    }
                    if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                        i++
                        params.add(readLambda())
                    }
                    chain[chain.lastIndex] = ArrayExpression(chain.last(), params)
                }
                else -> break@loop
            }
        }

        if (chain.size == 1) return chain.first()

        TODO("read sort elements by precedence, $chain, $symbols")
    }

    fun readLambda(): ExpressionList {
        // todo check for arrow
        val params = ArrayList<String>()
        val x = { a: Int, c: Int ->

        }
        var j = i
        while (true) {
            if (tokens.equals(j, TokenType.OPEN_CALL)) {
                j++
                TODO("parse destructuring")
            }
            if (tokens.equals(j, TokenType.SYMBOL, "->")) {
                TODO("")
            }
        }

        TODO("")
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