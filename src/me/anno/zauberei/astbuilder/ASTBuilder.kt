package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.flow.DoWhileLoop
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.WhileLoop
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.*

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

        private val supportedInfixFunctions = listOf(
            // these shall only be supported for legacy reasons: I dislike that their order of precedence isn't clear
            "shl", "shr", "ushr", "and", "or",

            // I like these:
            "in", "to", "step", "until",
            "is", "!is", "as", "as?"
        )
    }

    val AnyType = ClassType(root.getOrPut("Any"), emptyList())

    val imports = ArrayList<Package>()
    val keywords = ArrayList<String>()
    val names = ArrayList<String>()

    var currPackage = root
    var i = 0

    fun readPath(): Package {
        assert(tokens.equals(i, TokenType.NAME))
        var path = root.getOrPut(tokens.toString(i++))
        while (i < tokens.size && tokens.equals(i, ".")) {
            i++
            assert(tokens.equals(i, TokenType.NAME))
            path = path.getOrPut(tokens.toString(i++))
        }
        if (i < tokens.size && tokens.equals(i, ".*")) {
            i++
            path = path.getOrPut("*")
        }
        return path
    }

    fun readFileLevel() {
        loop@ while (i < tokens.size) {
            println("readFileLevel[$i]: ${tokens.err(i)}")
            when {
                tokens.equals(i, "package") -> {
                    i++; currPackage = readPath()
                }
                tokens.equals(i, "import") -> {
                    i++; imports.add(readPath())
                }

                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) ->
                    collectNames(fileLevelKeywords)

                tokens.equals(i, TokenType.OPEN_CALL) -> {
                    val isClass = keywords.remove("class")
                    val isFun = keywords.remove("fun")
                    val isObject = keywords.remove("object")
                    assert(isClass.toInt() + isFun.toInt() + isObject.toInt() == 1)
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeFirst()
                    when {
                        isClass -> {
                            currPackage = currPackage.getOrPut(name)
                            currPackage.keywords.addAll(packKeywords()); // safe keywords
                            val constructorParams = pushCall { readParamDeclarations() }
                            currPackage.primaryConstructorParams = constructorParams
                            if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                                pushBlock { readFileLevel() }
                            }
                            currPackage = currPackage.parent!!
                        }
                        isFun -> {
                            val keywords = packKeywords()

                            // parse optional <T, U>; todo cannot happen here, we confirmed () already
                            val typeParams = readFunctionTypeParameters()

                            // parse parameters (...)
                            val parameters = pushCall { readParamDeclarations() }

                            // optional return type
                            val returnType = if (tokens.equals(i, ":")) {
                                i++
                                readType()
                            } else null

                            // body (or just = expression)
                            val body = if (tokens.equals(i, "=")) {
                                i++ // skip =
                                readExpression()
                            } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                                pushBlock { readFunctionBody() }
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
                    val isInit = keywords.remove("init")
                    val isCompanion = keywords.remove("object")
                    assert(isInit.toInt() + isCompanion.toInt() == 1)
                    assert(names.isEmpty())
                    when {
                        isInit -> {
                            val body = pushBlock { readFunctionBody() }
                            currPackage.initialization += body.members
                        }
                        isCompanion -> {
                            assert("companion" in keywords)
                            val name = "Companion"
                            currPackage = currPackage.getOrPut(name)
                            currPackage.keywords.addAll(keywords); keywords.clear() // safe keywords
                            pushBlock { readFileLevel() }
                            currPackage = currPackage.parent!!
                        }
                        else -> throw IllegalStateException("Cannot happen")
                    }
                }
                tokens.equals(i, "=") -> {
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
            if (!tokens.equals(i, TokenType.STRING) &&
                tokens.equals(i, keyword)
            ) {
                keywords.add(keyword)
                i++
                return
            }
        }

        names.add(tokens.toString(i++))
    }

    fun readParamExpressions(): ArrayList<Expression> {
        val params = ArrayList<Expression>()
        if (i < tokens.size) {
            while (true) {
                params.add(readExpression())
                if (i < tokens.size && tokens.equals(i, TokenType.COMMA)) i++
                else break
            }
        }
        return params
    }

    fun readParamDeclarations(): List<Parameter> {
        val result = ArrayList<Parameter>()
        while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) ->
                    collectNames(paramLevelKeywords)

                tokens.equals(i, ":") -> {
                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(names.size == 1) { "Expected exactly one name, got $names" }
                    val name = names.removeLast()
                    i++ // skip ':'

                    val type = readType() // <-- handles generics now

                    val initialValue = if (i < tokens.size && tokens.equals(i, "=")) {
                        i++
                        readExpression()
                    } else null

                    result.add(Parameter(isVar, isVal, name, type, initialValue))
                    keywords.clear()

                    if (i < tokens.size && tokens.equals(i, TokenType.COMMA)) i++
                    else if (i < tokens.size) throw IllegalStateException("Expected comma or end")
                }
                else -> throw NotImplementedError("Unknown token in params at ${tokens.err(i)}")
            }
        }
        return result
    }

    fun readFunctionTypeParameters(): List<Parameter> {
        if (!tokens.equals(i, "<")) return emptyList()
        val params = mutableListOf<Parameter>()
        tokens.push(i++, TokenType.SYMBOL, "<", TokenType.SYMBOL, ">") {
            while (i < tokens.size) {
                assert(tokens.equals(i, TokenType.NAME)) { "Expected type parameter name" }
                val name = tokens.toString(i++)
                val type = if (tokens.equals(i, ":")) readType()
                else AnyType
                params.add(Parameter(false, true, name, type, null))
                if (i < tokens.size && tokens.equals(i, TokenType.COMMA)) i++
                else if (i < tokens.size) throw IllegalStateException("Unexpected symbol at ${tokens.err(i)}")
            }
            params
        }
        return params
    }

    fun readExpressionCondition(): Expression {
        return pushCall { readExpression() }
    }

    fun readBodyOrLine(): Expression {
        return if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock { readFunctionBody() }
        } else {
            readExpression()
        }
    }

    fun readPrefix(): Expression {

        val label =
            if (tokens.equals(i, TokenType.LABEL)) tokens.toString(i++)
            else null

        when {
            tokens.equals(i, TokenType.NUMBER) -> return NumberExpression(tokens.toString(i++))
            tokens.equals(i, TokenType.STRING) -> return StringExpression(tokens.toString(i++))

            tokens.equals(i, "null") -> {
                i++; return VariableExpression("null")
            }
            tokens.equals(i, "true") -> {
                i++; return VariableExpression("true")
            }
            tokens.equals(i, "false") -> {
                i++; return VariableExpression("false")
            }

            tokens.equals(i, "if") -> {
                i++
                val condition = readExpressionCondition()
                val ifTrue = readBodyOrLine()
                val ifFalse = if (tokens.equals(i, "else")) {
                    i++
                    readBodyOrLine()
                } else ExpressionList.empty
                return IfElseBranch(condition, ifTrue, ifFalse)
            }
            tokens.equals(i, "else") ->
                throw IllegalStateException("Unexpected else at ${tokens.err(i)}")
            tokens.equals(i, "while") -> {
                i++
                val condition = readExpressionCondition()
                val body = readBodyOrLine()
                return WhileLoop(condition, body, label)
            }
            tokens.equals(i, "do") -> {
                i++
                val body = readBodyOrLine()
                val condition = readExpressionCondition()
                return DoWhileLoop(body, condition, label)
            }
            tokens.equals(i, "when") -> {
                i++
                if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    TODO("read declaration, assignment or name at ${tokens.err(i)}")
                } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    val cases = ArrayList<WhenCase>()
                    pushBlock {
                        while (i < tokens.size) {
                            val nextArrow = tokens.findToken(i, TokenType.SYMBOL, "->")
                            assert(nextArrow != -1)
                            val condition = push(nextArrow) {
                                if (tokens.equals(i, "else")) null
                                else readExpression()
                            }
                            val expr = readBodyOrLine()
                            cases.add(WhenCase(condition, expr))
                        }
                    }
                    return WhenBranchExpression(cases)
                } else {
                    throw IllegalStateException("Unexpected token after when at ${tokens.err(i)}")
                }
            }
            tokens.equals(i, "return") -> {
                i++ // skip return
                if (i < tokens.size && tokens.isSameLine(i - 1, i)) {
                    val value = readExpression()
                    return ReturnExpression(value, label)
                } else return ReturnExpression(null, label)
            }
            tokens.equals(i, "throw") -> {
                i++ // skip throw
                val value = readExpression()
                return ThrowExpression(value)
            }
            tokens.equals(i, "break") -> {
                i++ // skip break
                return BreakExpression(label)
            }
            tokens.equals(i, "continue") -> {
                i++ // skip continue
                return ContinueExpression(label)
            }
            tokens.equals(i, TokenType.NAME) -> {
                val i0 = i
                val namePath = readPath()
                println("reading name: $namePath, ${tokens.err(i0)}")
                val typeArgs = if (isTypeArgsStartingHere(i)) {
                    readTypeArguments()
                } else emptyList()

                return if (i < tokens.size && tokens.equals(i, TokenType.OPEN_CALL)) {
                    // constructor or function call with type args
                    val start = i
                    val end = tokens.findBlockEnd(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
                    println(
                        "tokens for params: ${
                            (start..end).map { idx ->
                                "${tokens.getType(idx)}(${tokens.toString(idx)})"
                            }
                        }"
                    )
                    val args = pushCall { readParamExpressions() }
                    if (Character.isUpperCase(namePath.name!![0])) {
                        // heuristic: class constructor
                        ConstructorExpression(namePath, typeArgs, args)
                    } else {
                        CallExpression(VariableExpression(namePath.name), typeArgs, args)
                    }
                } else VariableExpression(namePath.name!!)
            }
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                // just something in brackets
                return pushCall { readExpression() }
            }
            else -> throw NotImplementedError("Unknown expression part at ${tokens.err(i)}")
        }
    }

    /**
     * check whether only valid symbols appear here
     * check whether brackets make sense
     *    for now, there is only ( and )
     * */
    private fun isTypeArgsStartingHere(i: Int): Boolean {
        if (i >= tokens.size) return false
        if (!tokens.equals(i, "<")) return false
        var depth = 1
        var i = i + 1
        while (depth > 0) {
            // todo support annotations here?
            // todo nullable types
            when {
                i >= tokens.size -> return false // reached end
                tokens.equals(i, TokenType.COMMA) -> {} // ok
                tokens.equals(i, TokenType.NAME) -> {} // ok
                tokens.equals(i, "<") -> depth++
                tokens.equals(i, ">") -> depth--
                tokens.equals(i, "?") -> {} // ok
                tokens.equals(i, TokenType.OPEN_BLOCK) ||
                        tokens.equals(i, TokenType.CLOSE_BLOCK) ||
                        tokens.equals(i, TokenType.OPEN_CALL) ||
                        tokens.equals(i, TokenType.CLOSE_CALL) ||
                        tokens.equals(i, TokenType.OPEN_ARRAY) ||
                        tokens.equals(i, TokenType.CLOSE_ARRAY) ||
                        tokens.equals(i, TokenType.SYMBOL) ||
                        tokens.equals(i, TokenType.STRING) ||
                        tokens.equals(i, TokenType.NUMBER) ||
                        tokens.equals(i, TokenType.APPEND_STRING) -> return false
                else -> throw NotImplementedError("Can ${tokens.err(i)} appear inside a type?")
            }
            i++
        }
        return i < tokens.size && tokens.equals(i, TokenType.OPEN_CALL)
    }

    fun readType(): Type {
        val path = readPath() // e.g. ArrayList
        val typeArgs = if (i < tokens.size && tokens.equals(i, "<")) {
            readTypeArguments()
        } else emptyList()
        val isNullable = i < tokens.size && tokens.equals(i, "?")
        if (isNullable) i++
        val baseType = ClassType(path, typeArgs)
        return if (isNullable) NullableType(baseType) else baseType
    }

    fun readTypeArguments(): List<Type> {
        assert(tokens.equals(i, "<"))
        i++ // consume '<'

        val args = mutableListOf<Type>()
        while (true) {
            args.add(readType()) // recursive type
            when {
                tokens.equals(i, TokenType.COMMA) -> i++
                tokens.equals(i, ">") -> {
                    i++ // consume '>'
                    break
                }
                else -> throw IllegalStateException("Expected , or > in type arguments, got ${tokens.err(i)}")
            }
        }
        return args
    }

    fun <R> pushCall(readImpl: () -> R): R {
        val result = tokens.pushCall(i++, readImpl)
        i++ // skip )
        return result
    }

    fun <R> pushArray(readImpl: () -> R): R {
        val result = tokens.pushArray(i++, readImpl)
        i++ // skip ]
        return result
    }

    fun <R> pushBlock(readImpl: () -> R): R {
        val result = tokens.pushBlock(i++, readImpl)
        i++ // skip }
        return result
    }

    fun <R> push(endTokenIdx: Int, readImpl: () -> R): R {
        val result = tokens.push(endTokenIdx, readImpl)
        i = endTokenIdx + 1 // skip }
        return result
    }

    fun readExpression(minPrecedence: Int = 0): Expression {
        var expr = readPrefix()

        // main elements
        loop@ while (i < tokens.size) {
            var isInfix = false
            val symbol = when (tokens.getType(i)) {
                TokenType.SYMBOL, TokenType.KEYWORD -> tokens.toString(i)
                TokenType.NAME -> {
                    isInfix = true
                    supportedInfixFunctions.firstOrNull { infix -> tokens.equals(i, infix) }
                        ?: break@loop
                }
                TokenType.APPEND_STRING -> "+"
                else -> break@loop
            }

            val op = operators[symbol] ?: break
            if (op.precedence < minPrecedence) break

            i++ // consume operator

            val rhs = readExpression(if (op.assoc == Assoc.LEFT) op.precedence + 1 else op.precedence)
            expr = if (isInfix) {
                val self = GetPropertyExpression(expr, op.symbol)
                CallExpression(self, emptyList(), listOf(rhs))
            } else {
                BinaryOp(expr, op.symbol, rhs)
            }
        }

        // postfix
        loop@ while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.OPEN_CALL) -> {
                    val params = pushCall { readParamExpressions() }
                    if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                        pushBlock { params += readLambda() }
                    }
                    expr = CallExpression(expr, emptyList(), params)
                }
                tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                    val params = pushArray { readParamExpressions() }
                    expr = ArrayExpression(expr, params)
                }
                tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                    val lambda = pushBlock { readLambda() }
                    expr = CallExpression(expr, emptyList(), listOf(lambda))
                }
                tokens.equals(i, "++") -> {
                    i++ // skip ++
                    expr = IncExpression(expr)
                }
                tokens.equals(i, "--") -> {
                    i++ // skip --
                    expr = DecExpression(expr)
                }
                tokens.equals(i, "!!") -> {
                    i++ // skip --
                    expr = NonNullExpression(expr)
                }
                else -> break@loop
            }
        }
        return expr
    }

    fun readLambda(): ExpressionList {
        val arrow = tokens.findToken(i, TokenType.SYMBOL, "->")
        if (arrow >= 0) {
            // todo only accept arrow if nothing weird is in-between
            TODO("parse destructuring at ${tokens.err(i)}")
        }
        return readFunctionBody()
    }

    fun readFunctionBody(): ExpressionList {
        val result = ArrayList<Expression>()
        while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.CLOSE_BLOCK) ->
                    throw IllegalStateException("} in the middle at ${tokens.err(i)}")
                tokens.equals(i, ";") -> i++ // skip
                tokens.equals(i, "val") -> result.add(readDeclaration(false))
                tokens.equals(i, "var") -> result.add(readDeclaration(true))
                tokens.equals(i, "lateinit") -> {
                    assert(tokens.equals(++i, "var"))
                    result.add(readDeclaration(true, isLateinit = true))
                }
                else -> {
                    result.add(readExpression())
                    println("block += ${result.last()}")
                }
            }
        }
        return ExpressionList(result)
    }

    fun readDeclaration(isVar: Boolean, isLateinit: Boolean = false): Expression {
        i++ // skip var/val
        assert(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i)
        val type = if (tokens.equals(i, ":")) {
            i++ // skip :
            readType()
        } else null
        val value = if (tokens.equals(i, "=")) {
            i++ // skip =
            readExpression()
        } else null
        return DeclarationExpression(name, type, value, isVar, isLateinit)
    }

    fun Boolean.toInt() = if (this) 1 else 0
}