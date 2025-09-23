package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.StringExpression
import me.anno.zauberei.astbuilder.flow.*
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.*
import kotlin.math.max
import kotlin.math.min

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
            "companion", "object", "constructor", "inline",
            "override", "abstract", "open", "final", "operator",
            "const", "lateinit", "annotation"
        )

        private val paramLevelKeywords = listOf(
            "private", "var", "val", "open", "override",
            "crossinline", "vararg", "final"
        )

        private val supportedInfixFunctions = listOf(
            // these shall only be supported for legacy reasons: I dislike that their order of precedence isn't clear
            "shl", "shr", "ushr", "and", "or",

            // I like these:
            "in", "to", "step", "until", "downTo",
            "is", "!is", "as", "as?"
        )
    }

    val AnyType = ClassType(root.getOrPut("Any"), emptyList())
    val NullableAnyType = NullableType(AnyType)

    val imports = ArrayList<Package>()
    val keywords = ArrayList<String>()

    // todo assign them appropriately
    val annotations = ArrayList<Annotation>()

    var currPackage = root
    var i = 0

    fun readPath(allowStarOperator: Boolean): Package {
        assert(tokens.equals(i, TokenType.NAME))
        var path = root.getOrPut(tokens.toString(i++))
        while (tokens.equals(i, ".")) {
            i++
            assert(tokens.equals(i, TokenType.NAME))
            path = path.getOrPut(tokens.toString(i++))
        }
        if (allowStarOperator && tokens.equals(i, ".*")) {
            i++
            path = path.getOrPut("*")
        }
        return path
    }

    private fun readClass() {
        assert(tokens.equals(++i, TokenType.NAME))
        val name = tokens.toString(i++)
        val clazz = currPackage.getOrPut(name)
        val keywords = packKeywords()
        clazz.typeParams = readFunctionTypeParameters()

        val privateConstructor = if (tokens.equals(i, "private")) i++ else -1
        if (tokens.equals(i, "constructor")) i++
        val constructorParams = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            pushCall { readParamDeclarations() }
        } else null

        readSuperCalls(clazz)

        clazz.primaryConstructorParams = constructorParams
        readClassBody(name, keywords)
    }

    private fun readInterface() {
        keywords.add("interface")
        assert(tokens.equals(++i, TokenType.NAME))
        val name = tokens.toString(i++)
        val clazz = currPackage.getOrPut(name)
        val keywords = packKeywords()
        clazz.typeParams = readFunctionTypeParameters()

        readSuperCalls(clazz)
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

        readSuperCalls(currPackage.getOrPut(name))
        readClassBody(name, keywords)
    }

    private fun readSuperCalls(clazz: Package) {
        if (tokens.equals(i, ":")) {
            i++ // skip :
            var endIndex = findEndOfSuperCalls(i)
            if (endIndex < 0) endIndex = tokens.size
            push(endIndex) {
                while (i < tokens.size) {
                    clazz.superCalls.add(readExpression())
                    readComma()
                }
            }
            i = endIndex // index of {
        }
    }

    private fun findEndOfSuperCalls(i0: Int): Int {
        var depth = 0
        for (i in i0 until tokens.size) {
            if (depth == 0) {
                if (tokens.equals(i, TokenType.OPEN_BLOCK) ||
                    tokens.equals(i, TokenType.OPEN_ARRAY) ||
                    tokens.equals(i, TokenType.KEYWORD) ||
                    tokens.equals(i, "val") ||
                    tokens.equals(i, "var") ||
                    tokens.equals(i, "companion") ||
                    tokens.equals(i, "override") ||
                    tokens.equals(i, "fun")
                ) {
                    return i
                }
            }
            when {
                tokens.equals(i, TokenType.OPEN_BLOCK) ||
                        tokens.equals(i, TokenType.OPEN_ARRAY) ||
                        tokens.equals(i, TokenType.OPEN_CALL) -> depth++
                tokens.equals(i, TokenType.CLOSE_BLOCK) ||
                        tokens.equals(i, TokenType.CLOSE_ARRAY) ||
                        tokens.equals(i, TokenType.CLOSE_CALL) -> depth--
            }
        }
        return -1
    }

    private fun readClassBody(name: String, keywords: List<String>) {
        currPackage = currPackage.getOrPut(name)
        currPackage.keywords.addAll(keywords)
        if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock {
                if ("enum" in keywords) {
                    val endIndex = readEnumBody()
                    i = min(endIndex + 1, tokens.size) // skipping over semicolon
                }
                readFileLevel()
            }
        }
        currPackage = currPackage.parent!!
    }

    private fun readEnumBody(): Int {
        var endIndex = tokens.findToken(i, ";")
        if (endIndex < 0) endIndex = tokens.size
        push(endIndex) {
            while (i < tokens.size) {
                // read enum value
                assert(tokens.equals(i, TokenType.NAME))
                val name = tokens.toString(i++)
                val typeParams = readTypeParams()
                val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    pushCall { readParamExpressions() }
                } else emptyList()
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    readClassBody(name, emptyList())
                }

                val call = CallExpression(VariableExpression(name), typeParams, params)
                currPackage.enumValues.add(call)
                readComma()
            }
        }
        return endIndex
    }

    var lastField: Field? = null

    private fun readVarValInClass(isVar: Boolean) {
        i++
        assert(tokens.equals(i, TokenType.NAME))
        var ownerType: Type? = null
        var name = tokens.toString(i++)
        val keywords = packKeywords()

        if (tokens.equals(i, ".")) {
            assert(tokens.equals(++i, TokenType.NAME))
            ownerType = UnresolvedType(name, emptyList())
            name = tokens.toString(i++)
        } else if (tokens.equals(i, "?.")) {
            assert(tokens.equals(++i, TokenType.NAME))
            ownerType = UnresolvedType(name, emptyList())
            ownerType = NullableType(ownerType)
            name = tokens.toString(i++)
        }


        val valueType = if (tokens.equals(i, ":")) {
            i++
            readType()
        } else null

        val initialValue = if (tokens.equals(i, "=")) {
            i++
            // todo find reasonable end index, e.g. fun, class, private, object, and limit to that
            readExpression()
        } else if (tokens.equals(i, "by")) {
            i++
            // todo find reasonable end index, e.g. fun, class, private, object, and limit to that
            DelegateExpression(readExpression())
        } else null

        if (ownerType == null) ownerType = ClassType(currPackage, emptyList())
        val field = Field(isVar, !isVar, ownerType, name, valueType, initialValue, keywords)
        println("read field $name: $valueType = $initialValue")
        currPackage.fields.add(field)
        lastField = field
    }

    private fun readFunction(): Function {
        i++ // skip 'fun'

        val keywords = packKeywords()

        // parse optional <T, U>
        val typeParameters = readFunctionTypeParameters()

        assert(tokens.equals(i, TokenType.NAME))
        val selfType = if (tokens.equals(i + 1, ".") ||
            tokens.equals(i + 1, "<") ||
            tokens.equals(i + 1, "?.")
        ) {
            if (tokens.equals(i + 1, ".")) {
                // avoid packing both type and function name into one
                val type = UnresolvedType(tokens.toString(i++), emptyList())
                assert(tokens.equals(i++, "."))
                type
            } else {
                var type = readType()
                if (tokens.equals(i, "?.")) {
                    type = NullableType(type)
                    i++
                } else {
                    assert(tokens.equals(i++, "."))
                }
                type
            }
        } else null

        assert(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i++)

        println("fun <$typeParameters> ${if (selfType != null) "$selfType." else ""}$name(...")

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
            selfType, name, typeParameters, parameters,
            returnType, body, keywords
        )
        currPackage.functions.add(function)
        return function
    }

    private fun readConstructor(): Constructor {
        i++ // skip 'fun'

        val keywords = packKeywords()

        // parse optional <T, U>
        val typeParameters = readFunctionTypeParameters()

        println("constructor <$typeParameters> (...")

        // parse parameters (...)
        assert(tokens.equals(i, TokenType.OPEN_CALL))
        val parameters = pushCall { readParamDeclarations() }

        // optional return type
        val superCall = if (tokens.equals(i, ":")) {
            i++
            assert(tokens.equals(i, "this") || tokens.equals(i, "super"))
            val name = tokens.toString(i++)
            val typeParams = readTypeParams()
            val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                pushCall { readParamExpressions() }
            } else emptyList()
            CallExpression(VariableExpression(name), typeParams, params)
        } else null

        // body (or just = expression)
        val body = if (tokens.equals(i, "=")) {
            i++ // skip =
            readExpression()
        } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock { readFunctionBody() }
        } else null

        val function = Constructor(
            typeParameters, parameters,
            superCall, body, keywords
        )
        currPackage.constructors.add(function)
        return function
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
                tokens.equals(i, "fun") -> {
                    if (tokens.equals(i + 1, "interface")) {
                        keywords.add("fun"); i++
                        readInterface()
                    } else readFunction()
                }
                tokens.equals(i, "interface") -> readInterface()
                tokens.equals(i, "constructor") -> readConstructor()
                tokens.equals(i, "typealias") -> readTypeAlias()
                tokens.equals(i, "var") -> readVarValInClass(true)
                tokens.equals(i, "val") -> readVarValInClass(false)
                tokens.equals(i, "init") -> {
                    assert(tokens.equals(++i, TokenType.OPEN_BLOCK))
                    currPackage.initialization += pushBlock { readFunctionBody() }
                }

                tokens.equals(i, "get") -> {
                    assert(tokens.equals(++i, TokenType.OPEN_CALL))
                    assert(tokens.equals(++i, TokenType.CLOSE_CALL))

                    i++ // skipping )

                    val field = lastField!!
                    field.privateGet = keywords.remove("private")

                    if (tokens.equals(i, ":")) {
                        i++
                        field.valueType = readType()
                    }

                    when {
                        tokens.equals(i, "=") -> {
                            i++ // skip =
                            field.getterExpr = readExpression()
                        }
                        tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                            field.getterExpr = pushBlock { readFunctionBody() } // will read the body
                        }
                        else -> throw IllegalStateException("Expected = or {} after get() at ${tokens.err(i)}")
                    }
                }
                tokens.equals(i, "set") -> {
                    i++ // skip set
                    val field = lastField!!
                    field.privateSet = keywords.remove("private")
                    if (tokens.equals(i, TokenType.OPEN_CALL)) {
                        assert(tokens.equals(++i, TokenType.NAME))
                        field.setterFieldName = tokens.toString(i++)
                        println("found set ${field.name}, ${field.setterFieldName}")
                        assert(tokens.equals(i++, TokenType.CLOSE_CALL))
                        field.setterExpr = if (tokens.equals(i, "=")) {
                            i++ // skip =
                            readExpression()
                        } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                            pushBlock { readFunctionBody() }
                        } else null
                    }// else println("found set without anything else, ${field.name}")
                }

                tokens.equals(i, "@") -> annotations.add(readAnnotation())

                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) ->
                    collectNames(fileLevelKeywords)

                else -> throw NotImplementedError("Unknown token at ${tokens.err(i)}")
            }
        }
    }

    fun readTypeAlias() {
        assert(tokens.equals(i++, "typealias"))
        val newName = readType()
        assert(tokens.equals(i++, "="))
        val oldName = readType()
        currPackage.typeAliases.add(TypeAlias(newName, oldName))
    }

    fun readAnnotation(): Annotation {
        // todo find save end: {} is not (yet?) supported by annotations
        assert(tokens.equals(i++, "@"))
        assert(tokens.equals(i, TokenType.NAME))
        val path = readPath(false)
        val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            pushCall { readParamExpressions() }
        } else emptyList()
        return Annotation(path, params)
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
                tokens.equals(i, "@") -> annotations.add(readAnnotation())
                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) -> {
                    val name = tokens.toString(i++)
                    if (name in paramLevelKeywords &&
                        (tokens.equals(i, TokenType.NAME) ||
                                tokens.equals(i, TokenType.KEYWORD))
                    ) {
                        keywords.add(name)
                        continue@loop
                    }

                    val isVar = keywords.remove("var")
                    val isVal = keywords.remove("val")
                    assert(tokens.equals(i++, ":")) { "Expected colon in var/val at ${tokens.err(i - 1)}" }

                    val type = readType() // <-- handles generics now

                    val initialValue = if (tokens.equals(i, "=")) {
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

    fun readGenericParams(): List<GenericParam> {
        val result = ArrayList<GenericParam>()
        loop@ while (i < tokens.size) {
            if (tokens.equals(i, TokenType.NAME) &&
                tokens.equals(i + 1, ":") &&
                tokens.equals(i + 2, TokenType.NAME)
            ) {
                val name = tokens.toString(i)
                i += 2
                result.add(GenericParam(name, readType()))
            } else if (tokens.equals(i, TokenType.NAME)) {
                result.add(GenericParam(null, readType()))
            } else throw IllegalStateException("Expected name: Type or name at ${tokens.err(i)}")
            readComma()
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
                val type = if (tokens.equals(i, ":")) {
                    i++ // skip :
                    readType()
                } else NullableAnyType
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
            // if just names and -> follow, read a single expression instead
            // if a destructuring and -> follow, read a single expression instead
            var j = i + 1
            var depth = 0
            arrowSearch@ while (j < tokens.size) {
                when {
                    tokens.equals(j, TokenType.OPEN_CALL) -> depth++
                    tokens.equals(j, TokenType.CLOSE_CALL) -> depth--
                    tokens.equals(j, "*") ||
                            tokens.equals(j, "?") ||
                            tokens.equals(j, ".") ||
                            tokens.equals(j, TokenType.COMMA) ||
                            tokens.equals(j, TokenType.NAME) -> {
                    }
                    tokens.equals(j, "->") -> {
                        if (depth == 0) return readExpression()
                    }
                    else -> break@arrowSearch
                }
                j++
            }

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
            tokens.equals(i, "@") -> {
                val annotation = readAnnotation()
                return AnnotatedExpression(annotation, readPrefix())
            }
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
            tokens.equals(i - 1, "::") && tokens.equals(i, "class") -> { // ::class
                i++; return VariableExpression("class")
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
            tokens.equals(i, "::") -> {
                i++
                // :: means a function of the current class
                return BinaryRevTypeOp(currPackage, "::", readExpression())
            }

            tokens.equals(i, "if") -> return readIfBranch()
            tokens.equals(i, "else") -> throw IllegalStateException("Unexpected else at ${tokens.err(i)}")
            tokens.equals(i, "while") -> return readWhileLoop(label)
            tokens.equals(i, "do") -> return readDoWhileLoop(label)
            tokens.equals(i, "for") -> return readForLoop(label)
            tokens.equals(i, "when") -> {
                i++
                return when {
                    tokens.equals(i, TokenType.OPEN_CALL) -> readWhenWithSubject()
                    tokens.equals(i, TokenType.OPEN_BLOCK) -> readWhenWithConditions()
                    else -> throw IllegalStateException("Unexpected token after when at ${tokens.err(i)}")
                }
            }
            tokens.equals(i, "try") -> return readTryCatch()
            tokens.equals(i, "return") -> return readReturn(label)
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
            tokens.equals(i, "super") -> {
                val namePath = tokens.toString(i++)
                return VariableExpression(namePath)
            }
            tokens.equals(i, "object") && tokens.equals(i + 1, TokenType.OPEN_BLOCK) -> {
                return readInlineClass0()
            }
            tokens.equals(i, "object") && tokens.equals(i + 1, ":") -> {
                return readInlineClass()
            }
            tokens.equals(i, TokenType.NAME) -> {
                val namePath = tokens.toString(i++)
                val typeArgs = readTypeParams()
                return if (
                    tokens.equals(i, TokenType.OPEN_CALL) &&
                    tokens.isSameLine(i - 1, i)
                ) {
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
            tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                return pushBlock { readLambda() }
            }
            else -> {
                tokens.printTokensInBlocks(max(i - 5, 0))
                throw NotImplementedError("Unknown expression part at ${tokens.err(i)}")
            }
        }
    }

    private fun readInlineClass0(): Expression {
        assert(tokens.equals(i++, "object"))
        assert(tokens.equals(i, TokenType.OPEN_BLOCK))

        val name = currPackage.generateName()
        val clazz = currPackage.getOrPut(name)

        readClassBody(name, emptyList())
        return ConstructorExpression2(clazz, emptyList(), emptyList())
    }

    private fun readInlineClass(): Expression {
        assert(tokens.equals(i++, "object"))
        assert(tokens.equals(i++, ":"))

        val name = currPackage.generateName()
        val clazz = currPackage.getOrPut(name)

        val bodyIndex = tokens.findToken(i, TokenType.OPEN_BLOCK)
        assert(bodyIndex > i)
        push(bodyIndex) {
            while (i < tokens.size) {
                clazz.superCalls.add(readExpression())
                readComma()
            }
        }
        i = bodyIndex
        readClassBody(name, emptyList())
        return ConstructorExpression2(clazz, emptyList(), emptyList())
    }

    private fun readIfBranch(): IfElseBranch {
        i++
        val condition = readExpressionCondition()
        val ifTrue = readBodyOrLine()
        val ifFalse = if (tokens.equals(i, "else") && !tokens.equals(i + 1, "->")) {
            i++
            readBodyOrLine()
        } else ExpressionList.empty
        return IfElseBranch(condition, ifTrue, ifFalse)
    }

    private fun readWhileLoop(label: String?): WhileLoop {
        i++
        val condition = readExpressionCondition()
        val body = readBodyOrLine()
        return WhileLoop(condition, body, label)
    }

    private fun readDoWhileLoop(label: String?): DoWhileLoop {
        i++
        val body = readBodyOrLine()
        assert(tokens.equals(i++, "while"))
        val condition = readExpressionCondition()
        return DoWhileLoop(body, condition, label)
    }

    private fun readForLoop(label: String?): Expression {
        i++ // skip for
        if (tokens.equals(i + 1, TokenType.OPEN_CALL)) {
            val names = ArrayList<String>()
            lateinit var iterable: Expression
            pushCall {
                assert(tokens.equals(i, TokenType.OPEN_CALL))
                pushCall {
                    while (i < tokens.size) {
                        assert(tokens.equals(i, TokenType.NAME))
                        names.add(tokens.toString(i++))
                        readComma()
                    }
                }
                // to do type?
                assert(tokens.equals(i++, "in"))
                iterable = readExpression()
                assert(i == tokens.size)
            }
            val body = readBodyOrLine()
            return DestructuringForLoop(names, iterable, body, label)
        } else {
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
    }

    private fun readWhenWithSubject(): WhenSubjectExpression {
        val subject = pushCall {
            when {
                tokens.equals(i, "val") -> readDeclaration(false, isLateinit = false)
                tokens.equals(i, "var") -> readDeclaration(true, isLateinit = false)
                else -> readExpression()
            }
        }
        assert(tokens.equals(i, TokenType.OPEN_BLOCK))
        val cases = ArrayList<SubjectWhenCase>()
        pushBlock {
            while (i < tokens.size) {
                val nextArrow = findNextArrow(i)
                assert(nextArrow != -1)
                val conditions = ArrayList<SubjectCondition?>()
                println("reading conditions, ${tokens.toString(i, nextArrow)}")
                push(nextArrow) {
                    while (i < tokens.size) {
                        println("  reading condition $nextArrow,$i,${tokens.err(i)}")
                        when {
                            tokens.equals(i, "else") -> {
                                i++; conditions.add(null)
                            }
                            tokens.equals(i, "in") || tokens.equals(i, "!in") -> {
                                val keyword = tokens.toString(i++)
                                val value = readExpression()
                                conditions.add(SubjectCondition(value, null, keyword))
                            }
                            tokens.equals(i, "is") || tokens.equals(i, "!is") -> {
                                val keyword = tokens.toString(i++)
                                val type = readType()
                                conditions.add(SubjectCondition(null, type, keyword))
                            }
                            else -> {
                                val value = readExpression()
                                conditions.add(SubjectCondition(value, null, null))
                            }
                        }
                        println("  read condition '${conditions.last()}'")
                        readComma()
                    }
                }
                println("conditions for body: $conditions")
                val body = readBodyOrLine()
                println("read body: $body")
                cases.add(SubjectWhenCase(conditions, body))
            }
        }
        return WhenSubjectExpression(subject, cases)
    }

    private fun findNextArrow(i0: Int): Int {
        var depth = 0
        var j = i0
        while (j < tokens.size) {
            when {
                tokens.equals(j, "is") ||
                        tokens.equals(j, "!is") ||
                        tokens.equals(j, "as") ||
                        tokens.equals(j, "as?") -> {
                    val originalI = i
                    i = j + 1 // after is/!is/as/as?
                    val type = readType()
                    println("skipping over type '$type'")
                    j = i - 1 // continue after the type; -1, because will be incremented immediately after
                    i = originalI
                }
                depth == 0 && tokens.equals(j, "->") -> {
                    println("found arrow at ${tokens.err(j)}")
                    return j
                }
                tokens.equals(j, TokenType.OPEN_BLOCK) ||
                        tokens.equals(j, TokenType.OPEN_ARRAY) ||
                        tokens.equals(j, TokenType.OPEN_CALL) -> depth++
                tokens.equals(j, TokenType.CLOSE_BLOCK) ||
                        tokens.equals(j, TokenType.CLOSE_ARRAY) ||
                        tokens.equals(j, TokenType.CLOSE_CALL) -> depth--
            }
            j++
        }
        return -1
    }

    private fun readWhenWithConditions(): WhenBranchExpression {
        val cases = ArrayList<WhenCase>()
        pushBlock {
            while (i < tokens.size) {
                val nextArrow = findNextArrow(i)
                assert(nextArrow > i) {
                    tokens.printTokensInBlocks(i)
                    "Missing arrow at ${tokens.err(i)} ($nextArrow vs $i)"
                }
                val condition = push(nextArrow) {
                    if (tokens.equals(i, "else")) null
                    else readExpression()
                }
                val body = readBodyOrLine()
                cases.add(WhenCase(condition, body))
            }
        }
        return WhenBranchExpression(cases)
    }

    private fun readReturn(label: String?): ReturnExpression {
        i++ // skip return
        println("reading return")
        if (i < tokens.size && tokens.isSameLine(i - 1, i) &&
            !tokens.equals(i, TokenType.COMMA)
        ) {
            val value = readExpression()
            println("  with value $value")
            return ReturnExpression(value, label)
        } else {
            println("  without value")
            return ReturnExpression(null, label)
        }
    }

    private fun readTryCatch(): TryCatchBlock {
        i++ // skip try
        val tryBody = readBodyOrLine()
        val catches = ArrayList<Catch>()
        while (tokens.equals(i, "catch")) {
            assert(tokens.equals(++i, TokenType.OPEN_CALL))
            val params = pushCall { readParamDeclarations() }
            assert(params.size == 1)
            val handler = readBodyOrLine()
            catches.add(Catch(params[0], handler))
        }
        val finally = if (tokens.equals(i, "finally")) {
            i++ // skip finally
            readBodyOrLine()
        } else null
        return TryCatchBlock(tryBody, catches, finally)
    }

    /**
     * check whether only valid symbols appear here
     * check whether brackets make sense
     *    for now, there is only ( and )
     * */
    private fun isTypeArgsStartingHere(i: Int): Boolean {
        if (i >= tokens.size) return false
        if (!tokens.equals(i, "<")) return false
        if (!tokens.isSameLine(i - 1, i)) return false
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
                tokens.equals(i, "?") ||
                        tokens.equals(i, "->") ||
                        tokens.equals(i, ".") ||
                        tokens.equals(i, "*") -> {
                    // ok
                }
                tokens.equals(i, TokenType.OPEN_CALL) -> depth++
                tokens.equals(i, TokenType.CLOSE_CALL) -> depth--
                tokens.equals(i, TokenType.OPEN_BLOCK) ||
                        tokens.equals(i, TokenType.CLOSE_BLOCK) ||
                        tokens.equals(i, TokenType.OPEN_ARRAY) ||
                        tokens.equals(i, TokenType.CLOSE_ARRAY) ||
                        tokens.equals(i, TokenType.SYMBOL) ||
                        tokens.equals(i, TokenType.STRING) ||
                        tokens.equals(i, TokenType.NUMBER) ||
                        tokens.equals(i, TokenType.APPEND_STRING) ||
                        tokens.equals(i, "val") ||
                        tokens.equals(i, "var") -> return false
                else -> throw NotImplementedError("Can ${tokens.err(i)} appear inside a type?")
            }
            i++
        }
        return true
    }

    fun readType(): Type {

        if (tokens.equals(i, "*")) {
            i++
            return NullableAnyType
        }

        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            val endI = tokens.findBlockEnd(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
            if (tokens.equals(endI + 1, "->")) {
                val parameters = pushCall { readGenericParams() }
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
        val typeArgs = readTypeParams()
        val isNullable = consumeNullable()
        val baseType = ClassType(path, typeArgs)
        return if (isNullable) NullableType(baseType) else baseType
    }

    private fun consumeNullable(): Boolean {
        val isNullable = tokens.equals(i, "?")
        if (isNullable) i++
        return isNullable
    }

    fun readTypeParams(): List<Type> {
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
        if (expr.toString() == "\"parentId\"") {
            RuntimeException("$expr").printStackTrace()
        }

        // main elements
        loop@ while (i < tokens.size) {
            var isInfix = false
            val symbol = when (tokens.getType(i)) {
                TokenType.SYMBOL, TokenType.KEYWORD -> tokens.toString(i)
                TokenType.NAME -> {
                    isInfix = true
                    val infix = supportedInfixFunctions.firstOrNull { infix -> tokens.equals(i, infix) }
                    // infix must be on the same line
                    if (infix == null || !tokens.isSameLine(i - 1, i)) break@loop
                    infix
                }
                TokenType.APPEND_STRING -> "+"
                else -> {
                    // postfix
                    expr = tryReadPostfix(expr) ?: break@loop
                    continue@loop
                }
            }
            if (symbol == "in" || symbol == "!in" || symbol == "is" || symbol == "!is") {
                // these must be on the same line
                if (!tokens.isSameLine(i - 1, i)) {
                    break@loop
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

                when (symbol) {
                    "as", "as?", "is", "!is" -> {
                        val rhs = readType()
                        expr = BinaryTypeOp(expr, op.symbol, rhs)
                    }
                    else -> {
                        val rhs = readExpression(if (op.assoc == Assoc.LEFT) op.precedence + 1 else op.precedence)
                        expr = if (isInfix) {
                            val self = GetPropertyExpression(expr, op.symbol)
                            CallExpression(self, emptyList(), listOf(rhs))
                        } else {
                            BinaryOp(expr, op.symbol, rhs)
                        }
                    }
                }
            }
        }

        return expr
    }

    fun tryReadPostfix(expr: Expression): Expression? {
        return when {
            i >= tokens.size || !tokens.isSameLine(i - 1, i) -> null
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
                        val name = tokens.toString(i++)
                        val type = if (tokens.equals(i, ":")) {
                            i++
                            readType()
                        } else null
                        variables.add(LambdaVariable(type, name))
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
        if (tokens.equals(i, TokenType.COMMA)) i++
        else if (i < tokens.size) throw IllegalStateException("Expected comma at ${tokens.err(i)}")
    }

    fun readFunctionBody(): ExpressionList {
        val result = ArrayList<Expression>()
        println("reading function body[$i], ${tokens.err(i)}")
        tokens.printTokensInBlocks(i)
        while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.CLOSE_BLOCK) ->
                    throw IllegalStateException("} in the middle at ${tokens.err(i)}")
                tokens.equals(i, ";") -> i++ // skip
                tokens.equals(i, "@") -> annotations.add(readAnnotation())
                tokens.equals(i, "val") -> result.add(readDeclaration(false))
                tokens.equals(i, "var") -> result.add(readDeclaration(true))
                tokens.equals(i, "fun") -> result.add(readFunction())
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

    private fun readDestructuring(isVar: Boolean, isLateinit: Boolean): DestructuringExpression {
        val names = ArrayList<String>()
        pushCall {
            while (i < tokens.size) {
                assert(tokens.equals(i, TokenType.NAME))
                names.add(tokens.toString(i++))
                if (tokens.equals(i, ":"))
                    throw NotImplementedError("Read type in destructuring at ${tokens.err(i)}")
                readComma()
            }
        }
        val value = if (tokens.equals(i, "=")) {
            i++ // skip =
            readExpression()
        } else throw IllegalStateException("Expected value for destructuring at ${tokens.err(i)}")
        return DestructuringExpression(names, value, isVar, isLateinit)
    }

    fun readDeclaration(isVar: Boolean, isLateinit: Boolean = false): Expression {
        i++ // skip var/val

        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            return readDestructuring(isVar, isLateinit)
        }

        assert(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i++) // todo name could be path...

        if (tokens.equals(i, ".")) {
            i++
            TODO("read val Vector3d.v get() = x+y")
        }

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
        if (!c) throw IllegalStateException("Assert failed at ${tokens.err(i)}")
    }

    inline fun assert(c: Boolean, msg: () -> String) {
        if (!c) throw IllegalStateException(msg())
    }
}