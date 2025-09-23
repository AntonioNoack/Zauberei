package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.StringExpression
import me.anno.zauberei.astbuilder.flow.*
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
            "companion", "object", "constructor", "inline"
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

    var currPackage = root
    var i = 0

    fun readPath(allowStarOperator: Boolean): Package {
        assert(tokens.equals(i, TokenType.NAME))
        var path = root.getOrPut(tokens.toString(i++))
        while (i < tokens.size && tokens.equals(i, ".")) {
            i++
            assert(tokens.equals(i, TokenType.NAME))
            path = path.getOrPut(tokens.toString(i++))
        }
        if (allowStarOperator && i < tokens.size && tokens.equals(i, ".*")) {
            i++
            path = path.getOrPut("*")
        }
        return path
    }

    private fun readClass() {
        assert(tokens.equals(++i, TokenType.NAME))
        val name = tokens.toString(i++)
        val keywords = packKeywords()
        val privateConstructor = if (tokens.equals(i, "private")) i++ else -1
        if (tokens.equals(i, "constructor")) i++
        val constructorParams = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            pushCall { readParamDeclarations() }
        } else null
        currPackage.getOrPut(name)
            .primaryConstructorParams = constructorParams
        readClassBody(name, keywords)
    }

    private fun readObject() {
        val name = if (tokens.equals(++i, TokenType.NAME)) {
            tokens.toString(i++)
        } else if (keywords.remove("companion")) {
            "Companion"
        } else throw IllegalStateException("Missing object name")
        keywords.add("object")

        val keywords = packKeywords()
        readClassBody(name, keywords)
    }

    private fun readClassBody(name: String, keywords: List<String>) {
        currPackage = currPackage.getOrPut(name)
        currPackage.keywords.addAll(keywords)
        if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock { readFileLevel() }
        }
        currPackage = currPackage.parent!!
    }

    var lastField: Field? = null

    private fun readVarValInClass(isVar: Boolean) {
        i++
        assert(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i++)
        val keywords = packKeywords()

        val type = if (tokens.equals(i, ":")) {
            i++
            readType()
        } else null

        val initialValue = if (i < tokens.size && tokens.equals(i, "=")) {
            i++
            // todo find reasonable end index, e.g. fun, class, private, object, and limit to that
            readExpression()
        } else null

        val field = Field(isVar, !isVar, name, type, initialValue, keywords)
        println("read field $name: $type = $initialValue")
        currPackage.fields.add(field)
        lastField = field
    }

    private fun readFunction() {
        i++ // skip 'fun'

        val keywords = packKeywords()

        // parse optional <T, U>
        val typeParameters = readFunctionTypeParameters()

        assert(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i++)

        println("fun <$typeParameters> $name(...")

        // parse parameters (...)
        assert(tokens.equals(i, TokenType.OPEN_CALL))
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
            name, typeParameters, parameters,
            returnType, body, keywords
        )
        currPackage.functions.add(function)
    }

    fun readFileLevel() {
        loop@ while (i < tokens.size) {
            println("readFileLevel[$i]: ${tokens.err(i)}")
            when {
                tokens.equals(i, "package") -> {
                    i++; currPackage = readPath(false)
                }
                tokens.equals(i, "import") -> {
                    i++; imports.add(readPath(true))
                }

                tokens.equals(i, "class") -> readClass()
                tokens.equals(i, "object") -> readObject()
                tokens.equals(i, "fun") -> readFunction()
                tokens.equals(i, "var") -> readVarValInClass(true)
                tokens.equals(i, "val") -> readVarValInClass(false)
                tokens.equals(i, "init") -> {
                    TODO()
                }

                tokens.equals(i, "get") -> TODO("read getter")
                tokens.equals(i, "set") -> TODO("read setter")

                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) ->
                    collectNames(fileLevelKeywords)

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

        throw IllegalStateException("Unknown keyword ${tokens.toString(i++)}")
    }

    fun readParamExpressions(): ArrayList<Expression> {
        val params = ArrayList<Expression>()
        while (i < tokens.size) {
            params.add(readExpression())
            println("read param: ${params.last()}")
            readComma()
        }
        return params
    }

    fun readParamDeclarations(): List<Parameter> {
        val result = ArrayList<Parameter>()
        loop@ while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) -> {
                    val name = tokens.toString(i++)
                    if (name in paramLevelKeywords) {
                        keywords.add(name)
                        continue@loop
                    }

                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(tokens.equals(i++, ":"))

                    val type = readType() // <-- handles generics now

                    val initialValue = if (i < tokens.size && tokens.equals(i, "=")) {
                        i++
                        readExpression()
                    } else null

                    result.add(Parameter(isVar, isVal, name, type, initialValue))
                    keywords.clear()

                    readComma()
                }
                else -> throw NotImplementedError("Unknown token in params at ${tokens.err(i)}")
            }
        }
        return result
    }

    fun readFunctionTypeParameters(): List<Parameter> {
        if (!tokens.equals(i, "<")) return emptyList()
        val params = ArrayList<Parameter>()
        tokens.push(i++, "<", ">") {
            while (i < tokens.size) {
                assert(tokens.equals(i, TokenType.NAME)) { "Expected type parameter name" }
                val name = tokens.toString(i++)
                val type = if (i < tokens.size && tokens.equals(i, ":")) {
                    i++ // skip :
                    readType()
                } else AnyType
                params.add(Parameter(false, true, name, type, null))
                readComma()
            }
        }
        assert(tokens.equals(i++, ">")) // skip >
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
            tokens.equals(i, "this") -> {
                i++; return VariableExpression("this")
            }
            tokens.equals(i, "!") -> {
                i++; return PrefixExpression("!", readExpression())
            }
            tokens.equals(i, "+") -> {
                i++; return readPrefix()
            }
            tokens.equals(i, "-") -> {
                i++; return PrefixExpression("-", readExpression())
            }
            tokens.equals(i, "++") -> {
                i++; return PrefixExpression("++", readExpression())
            }
            tokens.equals(i, "--") -> {
                i++; return PrefixExpression("--", readExpression())
            }

            tokens.equals(i, "if") -> {
                i++
                val condition = readExpressionCondition()
                val ifTrue = readBodyOrLine()
                val ifFalse = if (i < tokens.size && tokens.equals(i, "else")) {
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
            tokens.equals(i, "for") -> {
                i++ // skip for
                lateinit var name: String
                lateinit var iterable: Expression
                pushCall {
                    assert(tokens.equals(i, TokenType.NAME))
                    name = tokens.toString(i++)
                    // to do type?
                    assert(tokens.equals(i++, "in"))
                    iterable = readExpression()
                    assert(i == tokens.size)
                }
                val body = readBodyOrLine()
                return ForLoop(name, iterable, body, label)
            }
            tokens.equals(i, "when") -> {
                i++
                if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    val subject = pushCall { readExpression() }
                    assert(tokens.equals(i, TokenType.OPEN_BLOCK))
                    val cases = ArrayList<SubjectWhenCase>()
                    pushBlock {
                        while (i < tokens.size) {
                            val nextArrow = tokens.findToken(i, "->")
                            assert(nextArrow != -1)
                            val conditions = ArrayList<SubjectCondition?>()
                            push(nextArrow) {
                                while (i < tokens.size) {
                                    when {
                                        tokens.equals(i, "else") -> {
                                            i++; conditions.add(null)
                                        }
                                        tokens.equals(i, "in") ||
                                                tokens.equals(i, "!in") ||
                                                tokens.equals(i, "is") ||
                                                tokens.equals(i, "!is") -> {
                                            val keyword = tokens.toString(i++)
                                            val value = readExpression()
                                            conditions.add(SubjectCondition(value, keyword))
                                        }
                                        else -> {
                                            val value = readExpression()
                                            conditions.add(SubjectCondition(value, null))
                                        }
                                    }
                                    readComma()
                                }
                            }
                            val body = readBodyOrLine()
                            cases.add(SubjectWhenCase(conditions, body))
                        }
                    }
                    return WhenSubjectExpression(subject, cases)
                } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    val cases = ArrayList<WhenCase>()
                    pushBlock {
                        while (i < tokens.size) {
                            val nextArrow = tokens.findToken(i, "->")
                            assert(nextArrow > i) { "Missing arrow at ${tokens.err(i)} ($nextArrow vs $i)" }
                            val condition = push(nextArrow) {
                                if (tokens.equals(i, "else")) null
                                else readExpression()
                            }
                            val body = readBodyOrLine()
                            cases.add(WhenCase(condition, body))
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
                val namePath = tokens.toString(i++)
                val typeArgs = readTypeArguments()
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
                    if (Character.isUpperCase(namePath[0])) {
                        // heuristic: class constructor
                        ConstructorExpression(namePath, typeArgs, args)
                    } else {
                        CallExpression(VariableExpression(namePath), typeArgs, args)
                    }
                } else VariableExpression(namePath)
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
            if (i >= tokens.size) return false // reached end without closing the block
            println("  check ${tokens.err(i)} for type-args-compatibility")
            // todo support annotations here?
            when {
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
        return true
    }

    fun readType(): Type {
        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            val endI = tokens.findBlockEnd(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
            if (tokens.equals(endI + 1, "->")) {
                val parameters = pushCall { readParamDeclarations() }
                i = endI + 2 // skip ) and ->
                val returnType = readType()
                return LambdaType(parameters, returnType)
            } else {
                val baseType = pushCall { readType() }
                val isNullable = consumeNullable()
                return if (isNullable) NullableType(baseType) else baseType
            }
        }

        val path = readPath(false) // e.g. ArrayList
        val typeArgs = readTypeArguments()
        val isNullable = consumeNullable()
        val baseType = ClassType(path, typeArgs)
        return if (isNullable) NullableType(baseType) else baseType
    }

    private fun consumeNullable(): Boolean {
        val isNullable = i < tokens.size && tokens.equals(i, "?")
        if (isNullable) i++
        return isNullable
    }

    fun readTypeArguments(): List<Type> {
        if (i < tokens.size) {
            println("checking for type-args, ${tokens.err(i)}, ${isTypeArgsStartingHere(i)}")
        }
        if (isTypeArgsStartingHere(i)) {
            i++ // consume '<'

            val args = ArrayList<Type>()
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
        } else return emptyList()
    }

    fun <R> pushCall(readImpl: () -> R): R {
        val result = tokens.push(i++, TokenType.OPEN_CALL, TokenType.CLOSE_CALL, readImpl)
        i++ // skip )
        return result
    }

    fun <R> pushArray(readImpl: () -> R): R {
        val result = tokens.push(i++, TokenType.OPEN_ARRAY, TokenType.CLOSE_ARRAY, readImpl)
        i++ // skip ]
        return result
    }

    fun <R> pushBlock(readImpl: () -> R): R {
        val result = tokens.push(i++, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK, readImpl)
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
        println("prefix: $expr")

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
                else -> {
                    // postfix
                    expr = tryReadPostfix(expr) ?: break@loop
                }
            }

            println("symbol $symbol, valid? ${symbol in operators}")

            val op = operators[symbol]
            if (op == null) {
                // postfix
                expr = tryReadPostfix(expr) ?: break@loop
            } else {

                if (op.precedence < minPrecedence) break@loop

                i++ // consume operator

                val rhs = readExpression(if (op.assoc == Assoc.LEFT) op.precedence + 1 else op.precedence)
                expr = if (isInfix) {
                    val self = GetPropertyExpression(expr, op.symbol)
                    CallExpression(self, emptyList(), listOf(rhs))
                } else {
                    BinaryOp(expr, op.symbol, rhs)
                }
            }
        }

        return expr
    }

    fun tryReadPostfix(expr: Expression): Expression? {
        return when {
            i >= tokens.size -> null
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                val params = pushCall { readParamExpressions() }
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    pushBlock { params += readLambda() }
                }
                CallExpression(expr, emptyList(), params)
            }
            tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                val params = pushArray { readParamExpressions() }
                ArrayExpression(expr, params)
            }
            tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                val lambda = pushBlock { readLambda() }
                CallExpression(expr, emptyList(), listOf(lambda))
            }
            tokens.equals(i, "++") -> {
                i++ // skip ++
                PostfixExpression(expr, "++")
            }
            tokens.equals(i, "--") -> {
                i++ // skip --
                PostfixExpression(expr, "--")
            }
            tokens.equals(i, "!!") -> {
                i++ // skip !!
                PostfixExpression(expr, "!!")
            }
            else -> null
        }
    }

    fun readLambda(): Expression {
        val arrow = tokens.findToken(i, "->")
        if (arrow >= 0) {
            val variables = ArrayList<LambdaVariable>()
            tokens.push(arrow) {
                while (i < tokens.size) {
                    if (tokens.equals(i, TokenType.OPEN_CALL)) {
                        val names = ArrayList<String>()
                        pushCall {
                            while (i < tokens.size) {
                                if (tokens.equals(i, TokenType.NAME)) names.add(tokens.toString(i++))
                                else throw IllegalStateException("Expected name")
                                readComma()
                            }
                        }
                        variables.add(LambdaDestructuring(names))
                    } else if (tokens.equals(i, TokenType.NAME)) {
                        tokens.toString(i++)
                    } else throw NotImplementedError()
                    readComma()
                }
            }
            i++ // skip ->
            val body = readFunctionBody()
            return LambdaExpression(variables, body)
        } else return readFunctionBody()
    }

    fun readComma() {
        if (i < tokens.size && tokens.equals(i, TokenType.COMMA)) i++
        else if (i < tokens.size) throw IllegalStateException("Expected comma at ${tokens.err(i)}")
    }

    fun readFunctionBody(): ExpressionList {
        val result = ArrayList<Expression>()
        while (i < tokens.size) {
            println("reading function body[$i], ${tokens.err(i)}")
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
        val name = tokens.toString(i++)
        println("reading var/val $name")
        val type = if (tokens.equals(i, ":")) {
            println("skipping : for type")
            i++ // skip :
            readType().apply {
                println("type: $this")
            }
        } else {
            println("no type present")
            null
        }
        val value = if (tokens.equals(i, "=")) {
            i++ // skip =
            readExpression()
        } else null
        return DeclarationExpression(name, type, value, isVar, isLateinit)
    }

    // I hate Java-asserts...
    fun assert(c: Boolean) {
        if (!c) throw IllegalStateException()
    }

    inline fun assert(c: Boolean, msg: () -> String) {
        if (!c) throw IllegalStateException(msg())
    }
}