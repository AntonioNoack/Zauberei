package me.anno.support.cpp.ast.rich

import me.anno.langserver.VSCodeModifier
import me.anno.langserver.VSCodeType
import me.anno.support.Language
import me.anno.support.cpp.ast.rich.ArrayType.Companion.createArrayType
import me.anno.support.java.ast.JavaASTBuilder
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.mergeOrigins
import me.anno.zauber.ast.rich.controlflow.*
import me.anno.zauber.ast.rich.expression.*
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.unresolved.*
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.*
import me.anno.zauber.ast.rich.parser.ZauberASTBuilder.Companion.debug
import me.anno.zauber.ast.rich.parser.ZauberASTBuilder.Companion.unitInstance
import me.anno.zauber.ast.rich.parser.createCastExpression
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.TokenList
import me.anno.zauber.tokenizer.TokenType
import me.anno.zauber.types.BooleanUtils.not
import me.anno.zauber.types.LambdaParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.LambdaType
import me.anno.zauber.types.impl.unresolved.UnresolvedClassType

/**
 * Read C/C++ as if it was Zauber,
 * - so we can generate bindings to it
 * - so we can convert it to any other language
 * */
class CppASTBuilder(tokens: TokenList, root: Scope, val standard: CppStandard) :
    JavaASTBuilder(tokens, root, false, standard.kind()) {

    companion object {
        private val LOGGER = LogManager.getLogger(CppASTBuilder::class)

        private val builtInTypes by threadLocal {
            Types.run {
                mapOf(
                    "char" to Byte,
                    "unsigned char" to UByte,
                    "short" to Short,
                    "unsigned short" to UShort,
                    "int" to Int,
                    "unsigned" to UInt,
                    "unsigned int" to UInt,
                    "long" to Long,
                    "unsigned long" to ULong,
                    "float" to Float,
                    "double" to Double,
                    "long double" to Double,
                    "bool" to Boolean,
                    "void" to Unit,
                    "size_t" to Long,

                    "int8_t" to Byte,
                    "int16_t" to Short,
                    "int32_t" to Int,
                    "int64_t" to Long,

                    "uint8_t" to UByte,
                    "uint16_t" to UShort,
                    "uint32_t" to UInt,
                    "uint64_t" to ULong,
                )
            }
        }
    }

    override fun readFileLevel() {
        while (i < tokens.size) {
            // println("Reading next at file level: ${tokens.err(i)}")
            when {
                consumeIf("namespace") -> readNamespace()
                consumeIf("class") -> readStructOrClass(false)
                consumeIf("struct") -> {
                    readStructOrClass(true)
                    consume(";")
                }
                consumeIf("enum") -> readEnum()
                consumeIf("using") -> {
                    consume("namespace")
                    TODO("read this as an import with star")
                }
                consumeIf("typedef") -> readTypeAlias()
                consumeIf("const") -> {} // not const-expr, but a const type, more like final
                consumeIf("volatile") -> {}
                consumeIf(";") -> {} // ignore extra semicolons
                else -> readFieldOrMethod()
            }
        }
    }

    fun skipCallBlock(i0: Int): Int {
        var depth = 1
        check(tokens.equals(i0, TokenType.OPEN_CALL))
        var i = i0 + 1
        while (i < tokens.size && depth > 0) {
            when (tokens.getType(i++)) {
                TokenType.OPEN_CALL -> depth++
                TokenType.CLOSE_CALL -> depth--
                else -> {}
            }
        }
        return i
    }

    fun findTypeNameEnd(supportLambdaTypes: Boolean): Int {
        var end = i
        var depth = 0
        while (end < tokens.size) {
            when {
                tokens.equals(end, TokenType.OPEN_BLOCK) -> depth++
                tokens.equals(end, TokenType.CLOSE_BLOCK) -> depth--
                depth == 0 -> {
                    if (supportLambdaTypes && tokens.equals(end, "(") && tokens.equals(end + 1, "*")) {
                        end = skipCallBlock(end) // name
                        end = skipCallBlock(end) // parameters
                        return end
                    }
                    if (tokens.equals(end, ",", ";", "=", "(", "[", ")", "]")) {
                        return end
                    }
                }
            }
            end++
        }
        return end
    }

    fun readTypeUntil(endExcl: Int, allowAllStars: Boolean): Type {
        // println("Reading type $i .. $endExcl")
        return tokens.push(endExcl) {
            readTypeImpl(allowAllStars)
        }
    }

    fun readStructType(nameOverride: String?): Type {
        val name = nameOverride ?: run {
            // skip ahead and read name, if available
            val structEnd = tokens.findBlockEnd(i, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK)
            val name = tokens.push(tokens.totalSize) {
                if (tokens.equals(structEnd + 1, TokenType.NAME, TokenType.KEYWORD)) {
                    tokens.toString(structEnd + 1)
                } else "struct"
            }
            currPackage.generateName(name, origin(i))
        }
        return pushBlock(ScopeType.NORMAL_CLASS, name) { scope ->
            scope.setEmptyTypeParams()
            while (i < tokens.size) {
                if (consumeIf(";")) continue
                readFields(true)
            }
            scope.typeWithArgs
        }
    }

    fun readTypeImpl(allowAllStars: Boolean): Type {
        var type =
            if (consumeIf("struct")) readStructType(null)
            else readTypePath(null) ?: error("Expected type path at ${tokens.err(i)}")

        while (true) {
            type = when {
                consumeIf("* ") || consumeIf("*") -> type.ptr()
                allowAllStars && consumeIf(" *") -> type.ptr()
                tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                    val size = pushArray { readExpression() }
                    createArrayType(type, size)
                }
                else -> break
            }
        }
        return type
    }

    override fun readTypePath(selfType: Type?): Type? {
        var name0 = consumeName(VSCodeType.TYPE, 0)

        // handle weird types like
        //  - unsigned long long
        //  - long double
        //  - unsigned
        when (name0) {
            "unsigned", "long" -> {
                while (tokens.equals(i, TokenType.NAME, TokenType.KEYWORD)) {
                    name0 = "$name0 ${tokens.toString(i++)}"
                }
            }
        }

        var path = builtInTypes[name0]
            ?: genericParams.last()[name0]
            ?: currPackage.resolveTypeOrNull(name0, this)
            ?: (selfType as? ClassType)?.clazz?.resolveType(name0, this)
            ?: (selfType as? UnresolvedClassType)?.clazz?.resolveType(name0, this)
            ?: return null // Missing type '$name0' at ${tokens.err(i - 1)}

        while (tokens.equals(i, "::") && tokens.equals(i + 1, TokenType.NAME)) {
            path = (path as ClassType).clazz.getOrPut(tokens.toString(i + 1), null).typeWithArgs
            i += 2 // skip period and name
        }
        return path
    }

    override fun readSuperCalls(classScope: Scope, readBody: Boolean) {
        TODO("Not yet implemented")
    }

    data class TypeName(
        val preType: Type,
        val nameType: Type,
        val name: String
    )

    fun skipTypeKeywords() {
        while (true) {
            when {
                consumeIf("const") -> {}
                consumeIf("volatile") -> {}
                consumeIf("static") -> {}
                consumeIf("extern") -> addFlag(Flags.EXTERNAL)
                else -> break
            }
        }
    }

    fun readTypeAndName1(): TypeName {
        skipTypeKeywords()
        val typeNameEnd = findTypeNameEnd(supportLambdaTypes = true)

        if (tokens.equals(typeNameEnd - 1, TokenType.CLOSE_CALL)) {
            val returnTypeEnd = findTypeNameEnd(supportLambdaTypes = false)
            val returnType = readTypeUntil(returnTypeEnd, allowAllStars = true)
            var arraySizes: List<Expression> = emptyList()
            val name = pushCall {
                consume("*")
                val name = consumeName(VSCodeType.PROPERTY, VSCodeModifier.DECLARATION.flag)
                // we can have array sizes here...
                while (tokens.equals(i, TokenType.OPEN_ARRAY)) {
                    @Suppress("SuspiciousCollectionReassignment")
                    arraySizes += pushArray { readExpression() }
                }
                name
            }
            val parameters = pushCall {
                readParameterDeclarations(null, emptyList(), ParameterType.VALUE_PARAMETER)
                    .map { param -> LambdaParameter(param.name, param.type, param.origin) }
            }
            var type: Type = LambdaType(null, parameters, returnType)
            for (size in arraySizes) {
                type = createArrayType(type, size)
            }
            return TypeName(type, type, name)
        }

        var typeEnd = typeNameEnd - 1
        while (tokens.equals(typeEnd, " *", "*")) {
            typeEnd--
        }

        val preType = readTypeUntil(typeEnd, allowAllStars = false)
        return readFieldName(preType)
    }

    override fun readParameterDeclarations(
        selfType: Type?,
        extra: List<Parameter>,
        parameterType: ParameterType
    ): List<Parameter> {

        val parameters = ArrayList<Parameter>(extra)
        loop@ while (i < tokens.size) {

            val isVal = consumeIf("const")
            check(tokens.equals(i, TokenType.NAME, TokenType.KEYWORD)) {
                "Expected name, but got ${tokens.err(i)}"
            }

            val (_, type0, name) = readTypeAndName1()

            val origin = origin(i)
            var type = type0
            val isVararg = consumeIf("...") // todo is this supported in C/C++??
            if (isVararg) type = ClassType(Types.Array.clazz, listOf(type), origin)

            val flags = packFlags()
            val parameter = Parameter(
                parameters.size,
                if (isVal) ParameterMutability.VAL else ParameterMutability.VAR,
                if (isVararg) ParameterExpansion.VARARG else ParameterExpansion.NONE,
                parameterType, name, type, null, currPackage, origin
            )
            parameter.getOrCreateField(selfType, flags)
            parameters.add(parameter)

            readComma()
        }
        return parameters
    }

    fun readFieldName(preType: Type): TypeName {
        skipTypeKeywords()

        var nameType = preType

        while (true) {
            nameType = when {
                consumeIf(" *") || consumeIf("*") -> nameType.ptr()
                // we really should not need these:
                consumeIf(" **") -> nameType.ptr().ptr()
                consumeIf(" ***") -> nameType.ptr().ptr().ptr()
                consumeIf(" ****") -> nameType.ptr().ptr().ptr().ptr()
                else -> break
            }
        }

        val name = consumeName(VSCodeType.PROPERTY, VSCodeModifier.DECLARATION.flag)

        while (true) {
            nameType = when {
                tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                    val size = pushArray { readExpression() }
                    createArrayType(nameType, size)
                }
                else -> break
            }
        }

        return TypeName(preType, nameType, name)
    }

    fun readFields(canReadMultiple: Boolean): List<Pair<Field, AssignmentExpression?>> {
        return readFields(readTypeAndName1(), canReadMultiple)
    }

    fun readFields(typeAndName: TypeName, canReadMultiple: Boolean):
            List<Pair<Field, AssignmentExpression?>> {

        val origin = origin(i)
        val (preType, type, name) = typeAndName
        val initialValue = if (consumeIf("=")) readExpression() else null

        val field = currPackage.addField(
            null, false, isMutable = true, null,
            name, type, initialValue, packFlags(), origin
        )

        if (!canReadMultiple || !tokens.equals(i, TokenType.COMMA)) {
            return listOf(field to createAssignment(field))
        }

        val result = ArrayList<Pair<Field, AssignmentExpression?>>()
        while (consumeIf(",")) {

            val origin = origin(i)
            val (_, typeI, nameI) = readFieldName(preType)
            val initialValue = if (consumeIf("=")) readExpression() else null

            currPackage.addField(
                null, false, isMutable = true, null,
                nameI, typeI, initialValue, packFlags(), origin
            )
            result.add(field to createAssignment(field))
        }

        consume(";")
        return result
    }

    private fun createAssignment(field: Field): AssignmentExpression? {
        val origin = field.origin
        val initial = field.initialValue ?: return null
        return AssignmentExpression(FieldExpression(field, currPackage, origin), initial)
    }

    private fun readParameters(parameterType: ParameterType): List<Parameter> {
        // special case for a list without arguments
        if (i + 1 == tokens.size && consumeIf("void")) {
            return emptyList()
        }
        val parameters = ArrayList<Parameter>()
        while (i < tokens.size) {
            val origin = origin(i)
            val (_, type, name) = readTypeAndName1()
            val param = Parameter(
                parameters.size, name, parameterType,
                ParameterMutability.DEFAULT,
                type, currPackage, origin
            )
            val flags = packFlags()
            param.getOrCreateField(null, flags)
            parameters.add(param)
            readComma()
        }
        return parameters
    }

    private fun readParameterTypes(): List<Type> {
        // special case for a list without arguments
        if (i + 1 == tokens.size && consumeIf("void")) {
            return emptyList()
        }
        val parameters = ArrayList<Type>()
        while (i < tokens.size) {
            val (_, type, _) = readTypeAndName1()
            parameters.add(type)
            readComma()
        }
        return parameters
    }

    fun readFieldOrMethod() {
        val typeAndName = readTypeAndName1()
        if (tokens.equals(i, "(")) {
            readMethod(typeAndName)
        } else {
            readFields(typeAndName, true)
        }
    }

    /**
     * joins declaration and implementation
     * by making their scope-name unique
     * */
    private fun generateMethodScopeName(name: String): String {
        return if (standard.kind() == Language.C) name
        else {
            val i0 = i
            val paramTypes = readParameterTypes()
            i = i0
            paramTypes.joinToString(",", "$name(", ")") { rawType ->
                val trueType = rawType.resolvedName.resolve()
                StringStyles.removeStyles(trueType.toString())
            }
        }
    }

    fun readMethod(typeAndName: TypeName) {
        val origin = origin(i)
        val (_, returnType, name) = typeAndName

        val uniqueName = generateMethodScopeName(name)
        // println("Found method $uniqueName")

        val methodScope = currPackage.getOrPut(uniqueName, ScopeType.METHOD)
        val keywords = packFlags()

        if (methodScope.selfAsMethod?.body == null) {
            pushScope(methodScope) {
                val arguments = pushCall { readParameters(ParameterType.VALUE_PARAMETER) }

                val body = if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    readBodyOrExpression()
                } else {
                    consume(";")
                    null
                }

                val method = Method(
                    null, false, name, emptyList(), arguments,
                    methodScope, returnType, emptyList(), body, keywords, origin
                )
                methodScope.selfAsMethod = method
            }
        } else {

            skipCall() // skip value parameters

            if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                LOGGER.warn("Duplicate implementation for $uniqueName")
                pushBlock { i = tokens.size } // skip body
            } else {
                consume(";")
            }
        }
    }

    fun readEnum() {
        val enumName = if (tokens.equals(i, TokenType.NAME)) {
            consumeName(VSCodeType.ENUM, VSCodeModifier.DECLARATION.flag)
        } else {
            currPackage.generateName("enum", origin(i))
        }
        pushScope(enumName, ScopeType.ENUM_CLASS) { classScope ->
            classScope.setEmptyTypeParams()

            var ordinal = 0 // todo use value instead???
            pushBlock(classScope) {
                while (i < tokens.size) {
                    ordinal = readEnumValue(classScope, ordinal)
                    readComma()
                }
            }
        }
    }

    fun readEnumValue(classScope: Scope, ordinal: Int): Int {
        var ordinal = ordinal
        check(tokens.equals(i, TokenType.NAME)) {
            "Expected enum entry name at ${tokens.err(i)}"
        }
        val origin = origin(i)
        val valueName = tokens.toString(i++)
        val value = if (consumeIf("=")) {
            readExpression()
        } else null

        val keywords = packFlags()
        val entryScope = classScope.getOrPut(valueName, ScopeType.ENUM_ENTRY)
        entryScope.setEmptyTypeParams()

        // todo avoid duplicates?
        val numberExpr = value ?: NumberExpression((ordinal++).toString(), classScope, origin)
        val extraValueParameters = listOf(
            NamedParameter(numberExpr),
            NamedParameter(StringExpression(valueName, classScope, origin)),
        )
        val initialValue = ConstructorExpression(
            classScope, emptyList(),
            extraValueParameters,
            null, classScope, origin
        )
        entryScope.objectField = classScope.addField(
            null, false, isMutable = false, null,
            valueName, classScope.typeWithArgs, initialValue, keywords, origin
        )
        return ordinal
    }

    fun readStructOrClass(isStruct: Boolean) {
        if (isStruct) addFlag(Flags.CPP_STRUCT)
        check(tokens.equals(i, TokenType.NAME)) { "Expected class name at ${tokens.err(i)}" }
        val name = consumeName(VSCodeType.TYPE, VSCodeModifier.DECLARATION.flag)
        val classScope = currPackage.getOrPut(name, ScopeType.NORMAL_CLASS)
        classScope.setEmptyTypeParams()
        classScope.addFlags(packFlags())

        if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            // block can come later
            pushBlock(classScope) {
                readFileLevel()
            }
        }
    }

    fun readNamespace() {
        check(tokens.equals(i, TokenType.NAME)) { "Expected namespace name at ${tokens.err(i)}" }
        val name = tokens.toString(i++)
        val scope = currPackage.getOrPut(name, ScopeType.PACKAGE)
        pushBlock(scope) {
            readFileLevel()
        }
    }

    override fun readTypeAlias() {
        if (consumeIf("struct")) {
            // named structs in C++ are a little weird
            val structEnd = tokens.findBlockEnd(i, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK) + 1
            check(tokens.equals(structEnd, TokenType.NAME, TokenType.KEYWORD))
            val structName = tokens.toString(structEnd)
            readStructType(structName)
            consume(structName)
        } else {
            var (_, type, name) = readTypeAndName1()
            while (tokens.equals(i, TokenType.OPEN_ARRAY)) {
                val size = pushArray { readExpression() }
                type = createArrayType(type, size)
            }

            val alias = currPackage.getOrPut(name, ScopeType.TYPE_ALIAS)
            alias.setEmptyTypeParams()
            alias.selfAsTypeAlias = type
        }
        consume(";")
    }

    override fun readExpression(minPrecedence: Int): Expression {
        var expr = readPrefix()

        while (i < tokens.size) {

            var numSymbolTokens = 1
            val symbol = when (tokens.getType(i)) {
                TokenType.SYMBOL -> {
                    when (val str = tokens.toString(i)) {
                        "<", ">" -> when {
                            tokens.equals(i + 1, str) -> when {
                                tokens.equals(i + 2, "=") -> {
                                    numSymbolTokens = 3
                                    if (str == "<") "<<=" else ">>="
                                }
                                str == ">" && tokens.equals(i + 2, ">") -> {
                                    if (tokens.equals(i + 3, "=")) {
                                        numSymbolTokens = 4
                                        ">>>="
                                    } else {
                                        numSymbolTokens = 3
                                        ">>>"
                                    }
                                }
                                else -> {
                                    numSymbolTokens = 2
                                    if (str == "<") "<<" else ">>"
                                }
                            }
                            tokens.equals(i + 1, "=") -> when {
                                else -> {
                                    numSymbolTokens = 2
                                    "$str="
                                }
                            }
                            else -> str
                        }
                        else -> str
                    }
                }
                TokenType.COMMA -> return expr // cannot be used
                else -> {
                    expr = tryReadPostfix(expr) ?: break
                    continue
                }
            }

            val op = cOperators[symbol] ?: run {
                expr = tryReadPostfix(expr) ?: break
                continue
            }

            if (op.precedence < minPrecedence) break

            val origin = origin(i)
            i += numSymbolTokens // consume operator

            expr = when (symbol) {

                "&&", "||" -> {
                    val tmpName = currPackage.generateName("shortcut", origin)
                    val right = pushScope(tmpName, ScopeType.METHOD_BODY) { readRHS(op) }
                    if (symbol == "&&") shortcutExpressionI(expr, ShortcutOperator.AND, right, currPackage, origin)
                    else shortcutExpressionI(expr, ShortcutOperator.OR, right, currPackage, origin)
                }

                "?" -> {
                    val condition = expr
                    val ifTrue = pushScope(ScopeType.METHOD_BODY, "if") { readExpression() }
                    consume(":")
                    val ifFalse = pushScope(ScopeType.METHOD_BODY, "else") { readExpression() }
                    IfElseBranch(condition, ifTrue, ifFalse)
                }

                "." -> handleDot(expr, origin)
                "->" -> handleArrow(expr, origin)
                "::" -> handleScopeResolution(expr, origin)

                "=" -> AssignmentExpression(expr, readExpression())

                "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=" -> {
                    AssignIfMutableExpr(
                        expr, symbol,
                        shouldBeResolvable, shouldBeResolvable,
                        readExpression()
                    )
                }

                else -> {
                    val rhs = readRHS(op)
                    binaryOp(currPackage, expr, symbol, rhs, origin)
                }
            }
        }

        println("Read expr at ${tokens.err(i - 1)}")

        return expr
    }

    private fun looksLikeCast(): Boolean {
        val i0 = i
        val isCast = looksLikeCastImpl()
        i = i0
        if (false) println("Looks like a cast: $isCast, at ${tokens.err(i)}")
        return isCast
    }

    private fun looksLikeCastImpl(): Boolean {
        consume(TokenType.OPEN_CALL)
        val typeEnd = findTypeNameEnd(true)
        if (typeEnd == i) return false

        val bracketsEnd = tokens.findBlockEnd(i - 1, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
        if (bracketsEnd != typeEnd) return false

        try {
            readTypeUntil(typeEnd, allowAllStars = true)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    override fun readPrefix(): Expression {
        val origin = origin(i)

        println("Read prefix at ${tokens.err(i)}")

        return when {
            tokens.equals(i, "(") && looksLikeCast() -> {
                val type = pushCall {
                    val end = findTypeNameEnd(supportLambdaTypes = true)
                    readTypeUntil(end, allowAllStars = true)
                }
                val expr = readPrefix()
                createCastExpression(expr, currPackage, origin, type) { ifFalseScope ->
                    val debugInfoExpr = StringExpression(expr.toString(), ifFalseScope, origin)
                    val debugInfoParam = NamedParameter(debugInfoExpr)
                    CallExpression(
                        UnresolvedFieldExpression("throwNPE", shouldBeResolvable, ifFalseScope, origin),
                        emptyList(), listOf(debugInfoParam), origin
                    )
                }
            }

            consumeIf("sizeof") -> {
                val value = if (tokens.equals(i, "(") && !tokens.equals(i + 1, "*")) {
                    pushCall {
                        // can be a type or an expression
                        val type = readType(null, true)
                        if (type != null) {
                            GetClassFromTypeExpression(type, currPackage, origin)
                        } else {
                            readExpression()
                        }
                    }
                } else readPrefix()
                NamedCallExpression(
                    value, "sizeof", shouldBeResolvable,
                    emptyList(), emptyList(),
                    currPackage, origin
                )
            }

            consumeIf("++") ->
                createPrefixExpression(InplaceModifyType.INCREMENT, origin, readPrefix())
            consumeIf("--") ->
                createPrefixExpression(InplaceModifyType.DECREMENT, origin, readPrefix())

            consumeIf("*") || consumeIf(" *") ->
                NamedCallExpression(readPrefix(), "deref", nameAsImport("deref"), currPackage, origin)
            consumeIf("&") -> NamedCallExpression(readPrefix(), "addr", nameAsImport("deref"), currPackage, origin)
            consumeIf("!") -> readPrefix().not()

            tokens.equals(i, TokenType.NUMBER) ->
                NumberExpression(tokens.toString(i++), currPackage, origin)

            tokens.equals(i, TokenType.STRING) ->
                StringExpression(tokens.unescapeString(i++), currPackage, origin)

            // todo try to resolve field immediately
            tokens.equals(i, TokenType.NAME) -> {
                val name = tokens.toString(i++)
                UnresolvedFieldExpression(name, nameAsImport(name), currPackage, origin)
            }

            tokens.equals(i, TokenType.OPEN_CALL) -> {
                pushCall { readExpression() }
            }

            consumeIf("-") -> {
                if (tokens.equals(i, TokenType.NUMBER)) { // immediately apply it
                    NumberExpression("-" + tokens.toString(i++), currPackage, origin)
                } else {
                    val base = readExpression()
                    NamedCallExpression(base, "unaryMinus", currPackage, origin)
                }
            }

            tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                val origin0 = origin(i)
                pushBlock {
                    val list = ArrayList<Expression>()
                    while (i < tokens.size) {
                        list.add(readExpression())
                        readComma()
                    }
                    val origin = mergeOrigins(origin0, origin(i))
                    CreateStructExpression(list, currPackage, origin)
                }
            }

            else -> error("Unexpected token at ${tokens.err(i)}")
        }
    }

    private fun readLabel(): String? {
        if (i < tokens.size && !consumeIf(";")) {
            val label = tokens.toString(i++)
            consume(";")
            return label
        } else return null
    }

    private fun readReturn(label: String?): ReturnExpression {
        val origin = origin(i) // skip return
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading return")
        val expr = if (i < tokens.size && !tokens.equals(i, ",", ";")) {
            val value = readExpression()
            if (LOGGER.isDebugEnabled) LOGGER.debug("  with value $value")
            ReturnExpression(value, label, currPackage, origin)
        } else {
            if (LOGGER.isDebugEnabled) LOGGER.debug("  without value")
            ReturnExpression(unitInstance, label, currPackage, origin)
        }
        consume(";")
        return expr
    }

    private fun readThrow(): ThrowExpression {
        val origin = origin(i) // skip return
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading throw")
        val value = readExpression()
        if (LOGGER.isDebugEnabled) LOGGER.debug("  with value $value")
        val expr = ThrowExpression(value, currPackage, origin)
        consume(";")
        return expr
    }

    override fun tryReadPostfix(expr: Expression): Expression? {
        return when {
            // todo how about <>()?
            tokens.equals(i, "(") -> {
                val origin = origin(i)
                val valueParameters = pushCall { readValueParametersBody() }
                CallExpression(expr, null, valueParameters, origin)
            }
            tokens.equals(i, "[") -> {
                val origin = origin(i)
                val params = pushArray { readValueParametersBody() }
                NamedCallExpression(expr, "get", nameAsImport("get"), null, params, expr.scope, origin)
            }
            tokens.equals(i, "++") -> {
                val origin = origin(i++)
                createPostfixExpression(expr, InplaceModifyType.INCREMENT, origin)
            }
            tokens.equals(i, "--") -> {
                val origin = origin(i++)
                createPostfixExpression(expr, InplaceModifyType.DECREMENT, origin)
            }
            else -> null
        }
    }

    private fun handleDot(expr: Expression, origin: Long): Expression {
        val name = tokens.toString(i++)
        val rhs = UnresolvedFieldExpression(name, nameAsImport(name), expr.scope, origin)
        return DotExpression(expr, null, rhs, expr.scope, origin)
    }

    private fun handleArrow(expr: Expression, origin: Long): Expression {
        val deref = NamedCallExpression(expr, "deref", expr.scope, origin)
        return handleDot(deref, origin)
    }

    private fun handleScopeResolution(expr: Expression, origin: Long): Expression {
        val name = tokens.toString(i++)
        return GetMethodFromTypeExpression(
            (expr as UnresolvedFieldExpression).scope,
            name, currPackage, origin
        )
    }

    fun readBodyOrExpression(): Expression {
        return if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock(ScopeType.METHOD_BODY, null) {
                readMethodBody()
            }
        } else {
            readExprInNewScope()
        }
    }

    private fun readExprInNewScope(): Expression {
        val origin = origin(i)
        val tmpName = currPackage.generateName("expr", origin)
        return pushScope(tmpName, ScopeType.METHOD_BODY) {
            val end = tokens.findToken(i, ";")
            tokens.push(end) {
                readExpression()
            }
        }
    }

    override fun readMethodBody(): ExpressionList {
        val originalScope = currPackage
        val origin = origin(i)
        val result = ArrayList<Expression>()
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading method body[$i], ${tokens.err(i)}")
        if (debug) tokens.printTokensInBlocks(i)
        while (i < tokens.size) {
            val oldSize = result.size
            val oldNumFields = currPackage.fields.size
            when {
                consumeIf(";") -> {}
                consumeIf("if") -> result.add(readIfBranch())
                consumeIf("while") -> result.add(readWhileLoop(null))
                consumeIf("do") -> result.add(readDoWhileLoop(null))
                consumeIf("for") -> result.add(readForLoop(null))
                consumeIf("switch") -> result.add(readSwitch(null))
                consumeIf("try") -> result.add(readTryCatch(null))
                consumeIf("return") -> result.add(readReturn(null))
                consumeIf("throw") -> result.add(readThrow())
                consumeIf("continue") -> {
                    val label = resolveJumpLabel(readLabel())
                    result.add(ContinueExpression(label, currPackage, origin))
                }
                consumeIf("break") -> {
                    val label = resolveJumpLabel(readLabel())
                    result.add(BreakExpression(label, currPackage, origin))
                }

                tokens.equals(i, TokenType.NAME, TokenType.KEYWORD) -> {
                    // read declaration or assignment or method-call
                    //  -> all of those occurred already, and we could lookup based on the first token,
                    //  whether it is a type, method, or field

                    if (tokens.equals(i + 1, TokenType.NAME, TokenType.KEYWORD) ||
                        tokens.equals(i + 1, "*", " *", "* ") // float* hdr
                    ) {
                        consumeIf("struct") // optional in C++, mandatory in C -> skip it
                        // println("Reading field ${tokens.err(i)}")
                        val fields = readFields(canReadMultiple = true)
                        for ((_, assignment) in fields) {
                            if (assignment != null) result.add(assignment)
                        }
                    } else {
                        result.add(readExpression())
                    }

                    if (LOGGER.isDebugEnabled) LOGGER.debug("block += ${result.last()}")
                }

                else -> {
                    // sample: *s->img_buffer = 0;
                    // sample: (s->io.skip)(s->io_user_data, n-blen);
                    result.add(readExpression())
                }
            }

            // todo check whether this works correctly
            // if expression contains assignment of any kind, or a check-call
            //  we must create a new sub-scope,
            //  because the types of our fields may have changed
            if ((result.size > oldSize && result.last().splitsScope() && i < tokens.size) ||
                currPackage.fields.size > oldNumFields
            ) {

                val newFields = currPackage.fields.subList(oldNumFields, currPackage.fields.size)
                val newScope = currPackage.generate("split", ScopeType.METHOD_BODY)
                for (field in newFields.reversed()) {
                    field.moveToScope(newScope)
                }
                currPackage = newScope

                // read remainder, and place it in the subscope
                val remainder = readMethodBody()
                // move oldSize until result into new subscope, but keep Expression.scope the same!
                val remainderList = remainder.list as ArrayList<Expression>
                for (i in oldSize until result.size) {
                    remainderList.add(0, result[i])
                }
                result.subList(oldSize, result.size).clear()
                result.add(remainder)
                // else we can skip adding it, I think
            }
        }
        currPackage = originalScope // restore scope
        return ExpressionList(result, originalScope, origin)
    }

}
