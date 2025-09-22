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

// todo our current approach is pretty inflexible, and we should respect borders ([{<>}]) stricter, can we split this class,
//  and create a token sublist?

// I want macros... how could we implement them? learn about Rust macros
//  -> we get tokens as attributes in a specific pattern,
//  and after resolving the pattern, we can copy-paste these pattern variables as we please

//  -> we should be able to implement when() and for() using these

// todo getting started quickly is important for my Dopamine levels!

// todo first parse class structures, function contents later, so we can immediately fill in what a variable name belongs to (type, local, class member, ...)

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

                            // parse optional <T, U>
                            val typeParams = readFunctionTypeParameters()

                            // parse parameters (...)
                            val parameters = readParameters()

                            // optional return type
                            val returnType = if (tokens.equals(i, TokenType.SYMBOL, ":")) {
                                i++
                                readType()
                            } else null

                            // body (or just = expression)
                            val body = if (tokens.equals(i, TokenType.SYMBOL, "=")) {
                                i++
                                readExpressionWithPostfix()
                            } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                                tokens.pushBlock(i++) {
                                    readFunctionBody()
                                }
                            } else null

                            val function = Function(
                                names.removeFirst(),
                                typeParams, parameters,
                                returnType, body, keywords
                            )
                            currPackage.functions.add(function)
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
                    val initialValue = readExpressionWithPostfix()
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
                else -> throw NotImplementedError("Unknown token at ${tokens.err(i)}")
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
            when {
                tokens.equals(i, TokenType.NAME) -> collectNames(paramLevelKeywords)
                tokens.equals(i, TokenType.SYMBOL, ":") -> {
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    i++ // skip ':'

                    val type = readType() // <-- handles generics now

                    val initialValue = if (tokens.equals(i, TokenType.SYMBOL, "=")) {
                        i++
                        readExpressionWithPostfix()
                    } else null

                    result.add(Parameter(isVar, isVal, name, type, initialValue))
                    keywords.clear()

                    when {
                        tokens.equals(i, TokenType.COMMA) -> i++
                        tokens.equals(i, TokenType.CLOSE_CALL) -> {
                            i++
                            return result
                        }
                        else -> throw IllegalStateException("Unknown token in params at ${tokens.err(i)}")
                    }
                }
                else -> throw NotImplementedError("Unknown token in params at ${tokens.err(i)}")
            }
        }
        return result
    }

    fun readFunctionTypeParameters(): List<Package> {
        if (!tokens.equals(i, TokenType.SYMBOL, "<")) return emptyList()
        i++ // consume '<'
        val params = mutableListOf<Package>()
        while (true) {
            assert(tokens.equals(i, TokenType.NAME)) { "Expected type parameter name" }
            params.add(readPath())
            when {
                tokens.equals(i, TokenType.COMMA) -> i++
                tokens.equals(i, TokenType.SYMBOL, ">") -> {
                    i++; break
                }
                else -> throw IllegalStateException("Expected , or > in type parameters")
            }
        }
        return params
    }

    fun readExpressionCondition(): Expression {
        assert(tokens.equals(i++, TokenType.OPEN_CALL))
        val expr = readExpressionWithPostfix()
        assert(tokens.equals(i++, TokenType.CLOSE_CALL))
        return expr
    }

    fun readBodyOrLine(): Expression {
        return if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            i++
            readFunctionBody()
        } else {
            readExpressionWithPostfix()
        }
    }

    fun readPrefix(): Expression {
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
            tokens.equals(i, TokenType.NAME, "else") ->
                throw IllegalStateException("Unexpected else at ${tokens.err(i)}")
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
                } else {
                    throw IllegalStateException("Unexpected token after when at ${tokens.err(i)}")
                }
            }
            tokens.equals(i, TokenType.NAME) -> {
                val namePath = readPath()
                println("reading name: $namePath, ${tokens.err(i)}")
                val typeArgs = if (tokens.equals(i, TokenType.SYMBOL, "<")) {
                    readTypeArguments()
                } else emptyList()

                return if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    // constructor or function call with type args
                    i++ // consume (
                    val args = mutableListOf<Expression>()
                    if (!tokens.equals(i, TokenType.CLOSE_CALL)) {
                        do {
                            args.add(readExpressionWithPostfix())
                        } while (tokens.equals(i, TokenType.COMMA).also { if (it) i++ })
                    }
                    assert(tokens.equals(i, TokenType.CLOSE_CALL))
                    i++

                    if (Character.isUpperCase(namePath.name!![0])) {
                        // heuristic: class constructor
                        ConstructorExpression(namePath, typeArgs, args)
                    } else {
                        CallExpression(VariableExpression(namePath.name), typeArgs, args)
                    }
                } else {
                    VariableExpression(namePath.name!!)
                }
            }
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                // just something in brackets
                i++
                val inside = readPrefix()
                assert(tokens.equals(i, TokenType.CLOSE_CALL))
                i++
                return inside
            }
            else -> throw NotImplementedError("Unknown expression part ${tokens.getType(i)}, ${tokens.toString(i)}")
        }
    }

    fun readType(): Type {
        val path = readPath() // e.g. ArrayList
        val typeArgs = if (tokens.equals(i, TokenType.SYMBOL, "<")) {
            readTypeArguments()
        } else emptyList()
        return ClassType(path, typeArgs)
    }

    fun readTypeArguments(): List<Type> {
        assert(tokens.equals(i, TokenType.SYMBOL, "<"))
        i++ // consume '<'

        val args = mutableListOf<Type>()
        while (true) {
            args.add(readType()) // recursive type
            when {
                tokens.equals(i, TokenType.COMMA) -> i++
                tokens.equals(i, TokenType.SYMBOL, ">") -> {
                    i++ // consume '>'
                    break
                }
                else -> throw IllegalStateException("Expected , or > in type arguments, got ${tokens.err(i)}")
            }
        }
        return args
    }

    fun readExpressionWithoutPostfix(minPrecedence: Int = 0): Expression {
        var left = readPrefix()

        while (i < n && tokens.getType(i) == TokenType.SYMBOL) {
            val sym = tokens.toString(i)
            val op = operators[sym] ?: break
            if (op.precedence < minPrecedence) break

            i++ // consume operator

            val rhs = readExpressionWithoutPostfix(if (op.assoc == Assoc.LEFT) op.precedence + 1 else op.precedence)
            left = BinaryOp(left, op.symbol, rhs)
        }

        return left
    }

    fun readExpressionWithPostfix(minPrecedence: Int = 0): Expression {
        var expr = readExpressionWithoutPostfix(minPrecedence)
        loop@ while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.OPEN_CALL) -> {
                    val params = tokens.pushCall(i++) { readParamList() }
                    if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                        tokens.pushBlock(i++) { params += readLambda() }
                    }
                    expr = CallExpression(expr, emptyList(), params)
                }
                tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                    val params = tokens.pushArray(i++) { readParamList() }
                    expr = ArrayExpression(expr, params)
                }
                tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                    i++
                    expr = CallExpression(expr, emptyList(), listOf(readLambda()))
                }
                else -> break@loop
            }
        }
        return expr
    }

    fun readParamList(): ArrayList<Expression> {
        val params = ArrayList<Expression>()
        if (i < tokens.size) {
            while (true) {
                params.add(readExpressionWithPostfix())
                if (i < tokens.size && tokens.equals(i, TokenType.COMMA)) i++
                else break
            }
        }
        return params
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

            result.add(readExpressionWithoutPostfix())
        }
    }

    fun Boolean.toInt() = if (this) 1 else 0
}