package me.anno.zauber.ast.rich.parser

import me.anno.langserver.VSCodeModifier
import me.anno.langserver.VSCodeType
import me.anno.support.Language
import me.anno.utils.NumberUtils.toInt
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.Annotation
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.TokenListIndex.mergeOrigins
import me.anno.zauber.ast.rich.controlflow.*
import me.anno.zauber.ast.rich.expression.*
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.resolved.SuperExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.*
import me.anno.zauber.ast.rich.expression.unresolved.AssignIfMutableExpr.Companion.plusAssignName
import me.anno.zauber.ast.rich.expression.unresolved.AssignIfMutableExpr.Companion.plusName
import me.anno.zauber.ast.rich.expression.unresolved.MemberNameExpression.Companion.nameExpression
import me.anno.zauber.ast.rich.member.FieldDeclaration
import me.anno.zauber.ast.rich.member.FieldDeclarationI
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.*
import me.anno.zauber.expansion.Macro.evaluateMacro
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.TokenList
import me.anno.zauber.tokenizer.TokenType
import me.anno.zauber.types.BooleanUtils.not
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.arithmetic.NullType.typeOrNull
import kotlin.math.max

// I want macros... how could we implement them? learn about Rust macros
//  -> we get tokens as attributes in a specific pattern,
//  and after resolving the pattern, we can copy-paste these pattern variables as we please
//  -> we should be able to implement when() and for() using these
//  -> do we even need macros when we have a good language? :)

class ZauberASTBuilder(
    tokens: TokenList, root: Scope,
    language: Language = Language.ZAUBER
) : ZauberASTBuilderBase(tokens, root, false, language) {

    companion object {

        private val LOGGER = LogManager.getLogger(ZauberASTBuilder::class)

        val fileLevelKeywords = listOf(
            "enum", "private", "protected", "fun", "class", "data", "value",
            "companion", "object", "constructor", "inline",
            "override", "abstract", "open", "final", "operator",
            "const", "lateinit", "annotation", "internal", "inner", "sealed",
            "infix", "external"
        )

        val supportedInfixFunctions = listOf(
            // these shall only be supported for legacy reasons: I dislike that their order of precedence isn't clear
            "shl", "shr", "ushr", "and", "or", "xor",

            // I like these:
            "in", "to", "step", "until", "downTo",
            "is", "!is", "as", "as?"
        )

        var debug = false

        val unitInstance by threadLocal {
            val scope = Types.Unit.clazz
            val field = scope.getOrCreateObjectField(-1)
            FieldExpression(field, scope, -1)
        }
    }

    override fun readSuperCalls(classScope: Scope, readBody: Boolean) {
        if (consumeIf(":")) {
            var endIndex = findEndOfSuperCalls(i)
            if (endIndex < 0) endIndex = tokens.size
            if (readBody) push(endIndex) {
                while (i < tokens.size) {
                    classScope.superCalls.add(readSuperCall(classScope.typeWithArgs))
                    readComma()
                }
            } // else skip

            i = endIndex // index of {
        }

        addAnySuperCallIfNoneIsProvided(classScope, readBody)
    }

    /**
     * looks for new keywords, or reading depth < 0 by brackets
     * */
    private fun findEndOfSuperCalls(i0: Int): Int {
        var depth = 0
        for (i in i0 until tokens.size) {
            if (depth == 0) {
                if (tokens.equals(i, TokenType.OPEN_BLOCK) ||
                    tokens.equals(i, TokenType.OPEN_ARRAY) ||
                    tokens.equals(i, TokenType.KEYWORD) ||
                    fileLevelKeywords.any { tokens.equals(i, it) }
                ) return i
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

    private fun readFieldOrMethodSelfType(typeParameters: List<Parameter>, functionScope: Scope): Type? {
        if (tokens.equals(i + 1, ".") ||
            tokens.equals(i + 1, "<") ||
            tokens.equals(i + 1, "?.")
        ) {
            if (tokens.equals(i + 1, ".")) {
                // avoid packing both type and function name into one
                val name = tokens.toString(i++)
                val type = currPackage.resolveType(
                    name, typeParameters,
                    functionScope, this,
                )
                check(tokens.equals(i++, "."))
                return type
            } else {
                var type = readType(null, false)
                    ?: return null
                if (tokens.equals(i, "?.")) {
                    type = typeOrNull(type)
                    i++
                } else {
                    check(tokens.equals(i++, "."))
                }
                return type
            }
        } else return null
    }

    private fun readMethod(): Method {
        val origin = origin(i - 1) // on 'fun'

        if (currPackage.isInterface()) addFlag(Flags.OPEN)
        val keywords = packFlags()

        // parse optional <T, U>
        val classScopeIfInClass = if (currPackage.isClassOrObject()) currPackage else null
        val methodScope = skipTypeParametersToFindFunctionNameAndScope(origin)
        val typeParameters = readTypeParameterDeclarations(methodScope, true)

        check(tokens.equals(i, TokenType.NAME))
        val selfType0 = readFieldOrMethodSelfType(typeParameters, methodScope)
        val selfType = selfType0 ?: methodScope.selfType

        check(tokens.equals(i, TokenType.NAME))
        val name = consumeName(VSCodeType.METHOD, VSCodeModifier.DECLARATION.flag)

        if (LOGGER.isDebugEnabled) LOGGER.debug("fun <$typeParameters> $selfType.$name(...")

        // parse parameters (...)
        check(tokens.equals(i, TokenType.OPEN_CALL)) {
            "Expected () for method call $selfType.$name, but found ${tokens.err(i)}"
        }

        val valueParameters = pushScope(methodScope) {
            pushCall {
                val selfType = classScopeIfInClass?.typeWithArgs
                readParameterDeclarations(selfType, emptyList(), ParameterType.VALUE_PARAMETER)
            }
        }

        // optional return type
        var returnType =
            if (!tokens.equals(i, "=", ":")) Types.Unit // todo if there is a where, we first need to skip it
            else readTypeOrNull(selfType)

        // todo we can make yields and throws explicit to improve compiler performance and help programmers understand :)

        val extraConditions = readWhereConditions()

        val method = Method(
            selfType0, selfType0 != null, name, typeParameters, valueParameters, methodScope,
            returnType, extraConditions, null, keywords, origin
        )
        methodScope.selfAsMethod = method

        // body (or just = expression)
        method.body = pushScope(methodScope) {
            if (tokens.equals(i, "=")) {
                val origin = origin(i++) // skip =
                ReturnExpression(readExpression(), null, methodScope, origin)
            } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                if (returnType == null) returnType = Types.Unit
                pushBlock(methodScope) { readMethodBody() }
            } else {
                if (returnType == null) returnType = Types.Unit
                null
            }
        }

        popGenericParams()
        return method
    }

    override fun readFileLevel() {
        val size = tokens.size
        val helper = ZauberASTClassScanner(tokens)
        helper.i = i // we must start where we currently are, not from the start again
        helper.readFileLevel()
        assertEquals(size, helper.i)
        i = helper.i
    }

    override fun readAnnotation(): Annotation {
        val scope = readAnnotationScope()
        check(tokens.equals(i, TokenType.NAME))
        val path = readTypePath(null)
            ?: error("Expected type for annotation at ${tokens.err(i)}")
        val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            readValueParameters()
        } else emptyList()
        return Annotation(path, params, scope)
    }

    override fun consumeKeyword(): Int {
        return when {
            consumeIf("public") -> Flags.PUBLIC
            consumeIf("private") -> Flags.PRIVATE
            consumeIf("external") -> Flags.EXTERNAL
            consumeIf("open") -> Flags.OPEN
            consumeIf("override") -> Flags.OVERRIDE
            consumeIf("abstract") -> Flags.ABSTRACT
            consumeIf("operator") -> Flags.OPERATOR
            consumeIf("inline") -> Flags.INLINE
            consumeIf("infix") -> Flags.INFIX
            consumeIf("data") -> Flags.DATA_CLASS
            consumeIf("value") -> Flags.VALUE
            consumeIf("annotation") -> Flags.ANNOTATION
            consumeIf("sealed") -> Flags.SEALED
            consumeIf("const") -> Flags.CONSTEXPR
            consumeIf("final") -> Flags.FINAL
            consumeIf("lateinit") -> Flags.LATEINIT
            consumeIf("tailrec") -> 0 // just a compiler hint...
            else -> super.consumeKeyword()
        }
    }

    override fun readParameterDeclarations(
        selfType: Type?, extra: List<Parameter>,
        parameterType: ParameterType
    ): List<Parameter> {
        val parameters = ArrayList<Parameter>(extra)
        loop@ while (i < tokens.size) {

            readAnnotations()

            while ((tokens.equals(i, TokenType.KEYWORD) || tokens.equals(i, TokenType.NAME)) &&
                !tokens.equals(i + 1, ":")
            ) {
                val keyword = when {
                    consumeIf("private") -> Flags.PRIVATE
                    consumeIf("public") -> Flags.PUBLIC
                    consumeIf("protected") -> Flags.PROTECTED
                    consumeIf("override") -> Flags.OVERRIDE
                    consumeIf("open") -> Flags.OPEN
                    consumeIf("crossinline") -> Flags.CROSS_INLINE
                    else -> break
                }
                addFlag(keyword)
                semantic?.setLSType(i - 1, VSCodeType.KEYWORD, 0)
            }

            val i0 = i
            val isVararg = consumeIf("vararg", VSCodeType.KEYWORD, 0)
            val isConst = consumeIf("const", VSCodeType.KEYWORD, 0)
            val isVar = consumeIf("var", VSCodeType.KEYWORD, 0)
            val isVal = consumeIf("val", VSCodeType.KEYWORD, 0)

            check(isConst.toInt() + isVar.toInt() + isVal.toInt() <= 1) {
                "Only one of 'var', 'val' and 'const' must be present at ${tokens.err(i0)}"
            }

            val origin = origin(i)
            val name = consumeName(VSCodeType.PARAMETER, 0)
            consume(":")

            var type = readTypeNotNull(null, true) // <-- handles generics now
            if (isVararg) type = ClassType(Types.Array.clazz, listOf(type), origin)

            val initialValue = if (consumeIf("=")) readExpression() else null

            // println("Found $name: $type = $initialValue at ${resolveOrigin(i)}")

            val flags = packFlags()
            val parameter = Parameter(
                parameters.size,
                when {
                    isVar -> ParameterMutability.VAR
                    isVal -> ParameterMutability.VAL
                    else -> ParameterMutability.DEFAULT
                },
                if (isVararg) ParameterExpansion.VARARG else ParameterExpansion.NONE,
                parameterType, name, type, initialValue, currPackage, origin
            )
            parameter.getOrCreateField(null, flags)
            parameters.add(parameter)

            readComma()

        }
        return parameters
    }

    override fun readBodyOrExpression(label: String?): Expression {
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
                        if (depth == 0) {
                            return readExprInNewScope(label)
                        }
                    }
                    else -> break@arrowSearch
                }
                j++
            }

            val scopeName = currPackage.generateName("body", origin(i))
            pushBlock(ScopeType.METHOD_BODY, scopeName) { scope ->
                scope.jumpLabel = label
                readMethodBody()
            }
        } else {
            readExprInNewScope(label)
        }
    }

    private fun readExprInNewScope(label: String?): Expression {
        val origin = origin(i)
        val scopeName = currPackage.generateName("expr", origin)
        return pushScope(scopeName, ScopeType.METHOD_BODY) { scope ->
            scope.jumpLabel = label
            if (consumeIf(";")) unitInstance
            else readExpression()
        }
    }

    private fun readPrefix(): Expression {

        val origin = origin(i)

        val label = if (tokens.equals(i, TokenType.NAME, TokenType.KEYWORD) &&
            tokens.equals(i + 1, "@") &&
            tokens.equals(i + 2, "while", "do", "for") &&
            !tokens.equals(i, "this", "super", "break", "continue", "return")
        ) {
            val name = consumeName(VSCodeType.LABEL, 0)
            consume("@")
            name
        } else null

        return when {
            consumeIf("@", VSCodeType.DECORATOR, 0) -> {
                val annotation = readAnnotation()
                AnnotatedExpression(annotation, readPrefix())
            }
            consumeIf("null") -> SpecialValueExpression(SpecialValue.NULL, currPackage, origin)
            consumeIf("true") -> SpecialValueExpression(SpecialValue.TRUE, currPackage, origin)
            consumeIf("false") -> SpecialValueExpression(SpecialValue.FALSE, currPackage, origin)
            consumeIf("super") -> SuperExpression(resolveSuperLabel(readLabelMaybe()), false, currPackage, origin)
            consumeIf("this") -> ThisExpression(resolveThisLabel(readLabelMaybe()), currPackage, origin)
            tokens.equals(i, TokenType.NUMBER) -> NumberExpression(tokens.toString(i++), currPackage, origin)
            tokens.equals(i, TokenType.STRING) -> StringExpression(tokens.unescapeString(i++), currPackage, origin)
            consumeIf("!") -> {
                val base = readExpression()
                NamedCallExpression(base, "not", currPackage, origin)
            }
            consumeIf("+") -> {
                if (tokens.equals(i, TokenType.NUMBER)) { // just skip it
                    NumberExpression(tokens.toString(i++), currPackage, origin)
                } else {
                    val base = readExpression()
                    NamedCallExpression(base, "unaryPlus", currPackage, origin)
                }
            }
            consumeIf("-") -> {
                if (tokens.equals(i, TokenType.NUMBER)) { // immediately apply it
                    NumberExpression("-" + tokens.toString(i++), currPackage, origin)
                } else {
                    val base = readExpression()
                    NamedCallExpression(base, "unaryMinus", currPackage, origin)
                }
            }
            consumeIf("++") -> {
                val rhs = readRHS(unaryOperators["++"]!!)
                createPrefixExpression(InplaceModifyType.INCREMENT, origin, rhs)
            }
            consumeIf("--") -> {
                val rhs = readRHS(unaryOperators["--"]!!)
                createPrefixExpression(InplaceModifyType.DECREMENT, origin, rhs)
            }
            consumeIf("*") -> {
                ArrayToVarargsStar(readExpression())
            }
            consumeIf("::") -> {
                check(tokens.equals(i, TokenType.NAME))
                val name = tokens.toString(i++)
                // :: means a function of the current class
                DoubleColonLambda(currPackage, name, currPackage, origin)
            }

            consumeIf("if") -> readIfBranch()
            consumeIf("else") -> error("Unexpected 'else' at ${tokens.err(i - 1)}")
            consumeIf("while") -> readWhileLoop(label)
            consumeIf("do") -> readDoWhileLoop(label)
            consumeIf("for") -> readForLoop(label)
            consumeIf("when") -> {
                when {
                    tokens.equals(i, TokenType.OPEN_CALL) -> readWhenWithSubject(label)
                    tokens.equals(i, TokenType.OPEN_BLOCK) -> readWhenWithConditions(label)
                    else -> error("Unexpected token after when, expected '(' or '{' at ${tokens.err(i)}")
                }
            }
            consumeIf("try") -> readTryCatch()
            consumeIf("return") -> readReturn(label)
            consumeIf("throw") -> {
                ThrowExpression(readExpression(), currPackage, origin)
            }
            consumeIf("yield", VSCodeType.KEYWORD, 0) -> {
                YieldExpression(readExpression(), currPackage, origin)
            }
            consumeIf("async", VSCodeType.KEYWORD, 0) -> {
                AsyncExpression(readExpression(), currPackage, origin)
            }
            consumeIf("break") -> BreakExpression(resolveJumpLabel(readLabelMaybe()), currPackage, origin)
            consumeIf("continue") -> ContinueExpression(resolveJumpLabel(readLabelMaybe()), currPackage, origin)

            tokens.equals(i, "object") &&
                    (tokens.equals(i + 1, ":") || tokens.equals(i + 1, TokenType.OPEN_BLOCK)) -> {
                readInlineClass()
            }

            tokens.equals(i, TokenType.NAME, TokenType.KEYWORD) && !tokens.equals(i + 1, "@") -> {
                val vsCodeType = if (tokens.equals(i + 1, TokenType.OPEN_CALL, TokenType.OPEN_BLOCK))
                    VSCodeType.METHOD else VSCodeType.VARIABLE

                val i0 = i
                val namePath = consumeName(vsCodeType, 0)
                val typeParameters = readTypeParameters(null)

                if (tokens.equals(i, TokenType.OPEN_CALL) && tokens.isSameLine(i - 1, i)) {
                    // call or implicit macro
                    readNamedCall(namePath, i0, typeParameters, origin)
                } else if (tokens.equals(i, "!") && tokens.equals(i + 1, TokenType.OPEN_CALL)) {
                    // explicit macro
                    consume("!")
                    val valueParameters = readValueParameters()
                    evaluateMacro(namePath, i0, typeParameters, valueParameters, currPackage, origin)
                } else if (
                // todo validate that we have nothing before us...
                    tokens.equals(i, "::") && tokens.equals(i + 1, "class")) {
                    val i0 = i + 2
                    i-- // skipping over name
                    val type = readType(null, false)
                    if (type != null) {
                        consume("::")
                        consume("class")
                        check(i == i0)
                        GetClassFromTypeExpression(type, currPackage, origin)
                    } else {
                        val type = nameExpression(namePath, i++ /* skip name */, origin, currPackage)
                        consume("::")
                        consume("class")
                        check(i == i0)
                        GetClassFromValueExpression(type, currPackage, origin)
                    }
                } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    // e.g. run { ... }
                    val lambda = readLambdaBlock(null)
                    val nameExpr = nameExpression(namePath, i0, origin, currPackage)
                    CallExpression(
                        nameExpr, typeParameters,
                        listOf(NamedParameter(lambda)), origin
                    )
                } else {
                    check(typeParameters == null) { "Unexpected typeArgs at ${tokens.err(i)}" }
                    nameExpression(namePath, i0, origin, currPackage)
                }
            }
            // just something in brackets
            tokens.equals(i, TokenType.OPEN_CALL) -> pushCall { readExpression() }
            tokens.equals(i, TokenType.OPEN_BLOCK) -> readLambdaBlock(null)
            else -> {
                tokens.printTokensInBlocks(max(i - 5, 0))
                throw NotImplementedError("Unknown expression part at ${tokens.err(i)}")
            }
        }
    }

    private fun readLambdaBlock(selfType: Type?): Expression {
        val scopeName = currPackage.generateName("lambda", origin(i))
        return pushBlock(ScopeType.LAMBDA, scopeName) { lambdaScope ->
            val lambda = readLambda(selfType)
            lambdaScope.selfAsLambda = lambda
            lambda
        }
    }

    private fun getNextOuterTypeParams(): List<Parameter> {
        var scope: Scope? = currPackage
        while (scope != null) {
            val scopeType = scope.scopeType
            if (scope.hasTypeParameters && scopeType != null &&
                (scopeType.isMethodLike() || scopeType.isClassLike())
            ) return scope.typeParameters

            scope = scope.parentIfSameFile
        }
        return emptyList()
    }

    private fun readInlineClass(): Expression {
        val origin = origin(i)
        consume("object")

        val name = currPackage.generateName("inline", origin)
        val classScope = currPackage.getOrPut(name, tokens.fileName, ScopeType.INLINE_CLASS)
        classScope.setTypeParams(getNextOuterTypeParams())

        readSuperCalls(classScope, true)

        // println("Inline class has the following type-params: ${classScope.typeParameters}")

        readClassBody(name, Flags.NONE, ScopeType.INLINE_CLASS)
        return ConstructorExpression(
            classScope, emptyList(), emptyList(),
            null, currPackage, origin
        )
    }

    private fun readSuperCall(selfType: Type): SuperCall {
        val i0 = i
        val origin = origin(i)
        val type = readType(selfType, true) as? ClassType
            ?: error("SuperType must be a ClassType, at ${tokens.err(i0)}")

        val valueParams = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            readValueParameters()
        } else null

        val delegate = if (consumeIf("by")) readExpression() else null
        return SuperCall(type, valueParams, delegate, origin)
    }

    fun readForLoop(label: String?): Expression {
        lateinit var names: FieldDeclarationI
        lateinit var iterable: Expression
        pushCall {
            names = readForNames()
            consume("in")
            iterable = readExpression()
            check(i == tokens.size)
        }
        return pushScope(ScopeType.METHOD_BODY, "for") {
            val body = readBodyOrExpression(label ?: "")
            val extra = ArrayList<Expression>()
            createIteratorForLoop(iterable, body, label, extra, null) { initial ->
                createDestructuringAssignments(names, false, extra, initial)
            }
        }
    }

    private fun readWhenWithSubject(label: String?): Expression {
        val subject = pushCall {
            when {
                consumeIf("val") -> readFieldInMethod(false, isLateinit = false, currPackage)
                consumeIf("var") -> readFieldInMethod(true, isLateinit = false, currPackage)
                else -> readExpression()
            }
        }
        check(tokens.equals(i, TokenType.OPEN_BLOCK))
        val cases = ArrayList<SubjectWhenCase>()
        val childScope = pushBlock(ScopeType.WHEN_CASES, null) { childScope ->
            while (i < tokens.size) {
                if (cases.isNotEmpty()) pushHelperScope()
                val nextArrow = findNextArrow(i)
                check(nextArrow > i) {
                    "Expected nextArrow for whenCases starting at ${tokens.err(i)}, but only found $nextArrow"
                }
                val conditions = readSubjectConditions(nextArrow)
                val body = readBodyOrExpression(label)
                if (false) {
                    LOGGER.info("next case:")
                    LOGGER.info("  condition-scope: ${currPackage.pathStr}")
                    LOGGER.info("  body: $body")
                }
                cases.add(SubjectWhenCase(conditions, currPackage, body))
                consumeIf(";")
            }
            childScope
        }
        return whenSubjectToIfElseChain(childScope, subject, cases)
    }

    private fun readSubjectConditions(nextArrow: Int): List<SubjectCondition>? {
        val conditions = ArrayList<SubjectCondition>()
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading conditions, ${tokens.toString(i, nextArrow)}")
        var hadElse = false
        push(nextArrow) {
            while (i < tokens.size) {
                if (LOGGER.isDebugEnabled) LOGGER.debug("  reading condition $nextArrow,$i,${tokens.err(i)}")
                when {
                    consumeIf("else") -> {
                        hadElse = true
                        return@push null
                    }
                    tokens.equals(i, "in") || tokens.equals(i, "!in") -> {
                        semantic?.setLSType(i, VSCodeType.OPERATOR, 0)
                        val symbol =
                            if (tokens.toString(i++) == "in") SubjectConditionType.CONTAINS
                            else SubjectConditionType.NOT_CONTAINS
                        val value = readExpression()
                        val extra = if (consumeIf("if")) readExpression() else null
                        conditions.add(SubjectCondition(value, null, symbol, extra))
                    }
                    tokens.equals(i, "is") || tokens.equals(i, "!is") -> {
                        semantic?.setLSType(i, VSCodeType.OPERATOR, 0)
                        val symbol =
                            if (tokens.toString(i++) == "is") SubjectConditionType.INSTANCEOF
                            else SubjectConditionType.NOT_INSTANCEOF
                        val type = readType(null, false)
                        val extra = if (consumeIf("if")) readExpression() else null
                        conditions.add(SubjectCondition(null, type, symbol, extra))
                    }
                    else -> {
                        val value = readExpression()
                        val extra = if (consumeIf("if")) readExpression() else null
                        conditions.add(SubjectCondition(value, null, SubjectConditionType.EQUALS, extra))
                    }
                }
                if (LOGGER.isDebugEnabled) LOGGER.debug("  read condition '${conditions.last()}'")
                readComma()
            }
        }
        if (!hadElse && conditions.isEmpty()) {
            error("Missing conditions at ${tokens.err(i)}")
        }
        if (hadElse) return null
        return conditions
    }

    private fun pushHelperScope() {
        // push a helper scope for if/else differentiation...
        val type = ScopeType.WHEN_ELSE
        currPackage = currPackage.generate(type.name, type)
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
                    val type = readType(null, false)
                    if (LOGGER.isDebugEnabled) LOGGER.debug("skipping over type '$type'")
                    j = i - 1 // continue after the type; -1, because will be incremented immediately after
                    i = originalI
                }
                depth == 0 && tokens.equals(j, "->") -> {
                    if (LOGGER.isDebugEnabled) LOGGER.debug("found arrow at ${tokens.err(j)}")
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

    private fun readWhenWithConditions(label: String?): Expression {
        val origin = origin(i)
        val cases = ArrayList<WhenCase>()
        pushBlock(ScopeType.WHEN_CASES, null) {
            while (i < tokens.size) {
                if (cases.isNotEmpty()) pushHelperScope()

                val nextArrow = findNextArrow(i)
                check(nextArrow > i) {
                    tokens.printTokensInBlocks(i)
                    "Missing arrow at ${tokens.err(i)} ($nextArrow vs $i)"
                }

                val condition = push(nextArrow) {
                    if (consumeIf("else")) null
                    else readExpression()
                }

                val body = readBodyOrExpression(label)
                cases.add(WhenCase(condition, body))
            }
        }

        return whenBranchToIfElseChain(cases, currPackage, origin)
    }

    private fun readReturn(label: String?): ReturnExpression {
        val origin = origin(i - 1)
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading return")
        if (i < tokens.size && tokens.isSameLine(i - 1, i) &&
            !tokens.equals(i, ",", ";")
        ) {
            val value = readExpression()
            if (LOGGER.isDebugEnabled) LOGGER.debug("  with value $value")
            return ReturnExpression(value, label, currPackage, origin)
        } else {
            if (LOGGER.isDebugEnabled) LOGGER.debug("  without value")
            return ReturnExpression(unitInstance, label, currPackage, origin)
        }
    }

    private fun nullExpr(scope: Scope, origin: Long): SpecialValueExpression {
        return SpecialValueExpression(SpecialValue.NULL, scope, origin)
    }

    private fun isNotNullCondition(expr: Expression, scope: Scope, origin: Long): Expression {
        val nullExpr = nullExpr(scope, origin)
        return CheckEqualsOp(expr, nullExpr, byPointer = true, negated = true, null, scope, origin)
    }

    override fun readExpression(minPrecedence: Int): Expression {
        var expr = readPrefix()
        if (LOGGER.isDebugEnabled) LOGGER.debug("prefix: $expr")
        // println("expr from prefix[$i < ${tokens.size}]: $expr")

        // main elements
        loop@ while (i < tokens.size) {
            var isInfix = false
            var opLength = 1
            val symbol = when (tokens.getType(i)) {
                TokenType.SYMBOL, TokenType.KEYWORD -> {
                    if (tokens.equals(i, "?") && tokens.equals(i + 1, ".")) {
                        opLength = 2
                        "?."
                    } else tokens.toString(i)
                }
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
            when (symbol) {
                "in", "!in", "is", "!is", "+", "-" -> {
                    // these must be on the same line
                    if (!tokens.isSameLine(i - 1, i)) {
                        break@loop
                    }
                }
            }

            if (LOGGER.isDebugEnabled) LOGGER.debug("symbol $symbol, valid? ${symbol in operators}")

            val op = operators[symbol]
            if (op == null) {
                // postfix
                expr = tryReadPostfix(expr) ?: break@loop
            } else {

                if (op.precedence < minPrecedence) break@loop

                val origin = origin(i)
                i += opLength // consume operator

                val scope = currPackage
                expr = when (symbol) {
                    "as" -> createCastExpression(
                        expr, scope, origin,
                        readTypeNotNull(null, true)
                    ) { ifFalseScope ->
                        val debugInfoExpr = StringExpression(expr.toString(), ifFalseScope, origin)
                        val debugInfoParam = NamedParameter(debugInfoExpr)
                        CallExpression(
                            UnresolvedFieldExpression("throwNPE", shouldBeResolvable, ifFalseScope, origin),
                            emptyList(), listOf(debugInfoParam), origin
                        )
                    }
                    "as?" -> createCastExpression(
                        expr, scope, origin,
                        readTypeNotNull(null, true)
                    ) { scope -> nullExpr(scope, origin) }
                    "is" -> {
                        val type = readTypeNotNull(null, true)
                        IsInstanceOfExpr(expr, type, scope, origin)
                    }
                    "!is" -> {
                        val type = readTypeNotNull(null, true)
                        IsInstanceOfExpr(expr, type, scope, origin).not()
                    }
                    "?:" -> createBranchExpression(
                        expr, scope, origin,
                        { fieldExpr -> isNotNullCondition(fieldExpr, scope, origin) },
                        { fieldExpr, scope -> fieldExpr.clone(scope) }, // not null -> just the field
                        { scope -> pushScope(scope) { readExpression() } },
                    )
                    "?." -> createBranchExpression(
                        expr, scope, origin,
                        { fieldExpr -> isNotNullCondition(fieldExpr, scope, origin) },
                        { fieldExpr, scope ->
                            pushScope(scope) {
                                handleDotOperator(fieldExpr.clone(scope))
                            }
                        },
                        { scope -> nullExpr(scope, origin) },
                    )
                    "." -> handleDotOperator(expr)
                    "&&", "||" -> handleShortcutOperator(expr, symbol, op, scope, origin)
                    "::" -> {
                        if (tokens.equals(i, "class")) {
                            when (expr) {
                                is UnresolvedFieldExpression -> {
                                    val i0 = i + 1
                                    i -= 2 // skipping over :: and name
                                    val type = readTypeNotNull(null, false)
                                    check(tokens.equals(i++, "::"))
                                    check(tokens.equals(i++, "class"))
                                    check(i == i0)
                                    GetClassFromTypeExpression(type, scope, origin)
                                }
                                else -> throw NotImplementedError("Use left side as type ($expr, ${expr.javaClass.simpleName}), then generate ::class")
                            }
                        } else {
                            val rhs = readRHS(op)
                            binaryOp(currPackage, expr, op.symbol, rhs)
                        }
                    }
                    else -> {
                        // println("Reading RHS, symbol: $symbol")
                        val rhs = readRHS(op)
                        if (isInfix) {
                            val param = NamedParameter(rhs)
                            NamedCallExpression(
                                expr, op.symbol, nameAsImport(op.symbol), null,
                                listOf(param), expr.scope,
                                mergeOrigins(expr.origin, rhs.origin)
                            )
                        } else {
                            binaryOp(currPackage, expr, op.symbol, rhs)
                        }
                    }
                }
            }
        }

        return expr
    }

    private fun tryReadPostfix(expr: Expression): Expression? {
        return when {
            i >= tokens.size || !tokens.isSameLine(i - 1, i) -> null
            tokens.equals(i, TokenType.OPEN_CALL) && tokens.equals(i, "(") -> {
                // OPEN_CALL without ( must be ignored, that's not a valid function call
                val origin = origin(i)
                val params = readValueParameters()
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    params += NamedParameter(readLambdaBlock(null))
                }
                CallExpression(expr, null, params, origin)
            }
            tokens.equals(i, "!") && tokens.equals(i + 1, TokenType.OPEN_CALL) -> {
                consume("!")
                val valueParameters = pushCall { readValueParameters() }
                evaluateMacro(expr, null, valueParameters, origin(i))
            }
            tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                val origin = origin(i)
                val params = pushArray { readValueParametersBody() }
                if (consumeIf("=")) {
                    val value = NamedParameter(readExpression())
                    NamedCallExpression(
                        expr, "set", nameAsImport("set"),
                        null, params + value, expr.scope, origin
                    )
                } else if (tokens.equals(i, TokenType.SYMBOL) && tokens.endsWith(i, '=') &&
                    !(tokens.equals(i, "==", "!=") || tokens.equals(i, "!==", "==="))
                ) {
                    val symbol = tokens.toString(i++)
                    val value = readExpression()
                    val call = NamedCallExpression(
                        expr, "get/set", nameAsImport("get") + nameAsImport("set"),
                        null, params, expr.scope, origin
                    )
                    AssignIfMutableExpr(
                        call, symbol,
                        nameAsImport(plusName(symbol)),
                        nameAsImport(plusAssignName(symbol)),
                        value
                    )
                } else {
                    // array access is a getter
                    NamedCallExpression(
                        expr, "get", nameAsImport("get"),
                        null, params, expr.scope, origin
                    )
                }
            }
            tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                val origin = origin(i)
                val lambdaParam = NamedParameter(readLambdaBlock(null))
                CallExpression(expr, null, listOf(lambdaParam), origin)
            }
            consumeIf("++") -> createPostfixExpression(expr, InplaceModifyType.INCREMENT, origin(i - 1))
            consumeIf("--") -> createPostfixExpression(expr, InplaceModifyType.DECREMENT, origin(i - 1))
            consumeIf("!!") -> EnsureNotNullExpression(expr, currPackage, origin(i - 1))
            else -> null
        }
    }

    private fun readLambdaVariables(selfType: Type?, arrow: Int): List<LambdaVariable> {
        val variables = ArrayList<LambdaVariable>()
        tokens.push(arrow) {
            while (i < tokens.size) {
                if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    val origin0 = origin(i)
                    val names = ArrayList<LambdaVariable>()
                    pushCall {
                        while (i < tokens.size) {
                            check(tokens.equals(i, TokenType.NAME)) {
                                "Expected lambda-variable name at ${tokens.err(i)}"
                            }
                            names.add(readLambdaVariable(selfType))
                            readComma()
                        }
                    }
                    val name = "__ld${variables.size}"
                    val field = currPackage.addField( // this is more of a parameter...
                        null, false, isMutable = false, null,
                        name, null, null, Flags.SYNTHETIC, origin0
                    )
                    val origin = mergeOrigins(origin0, origin(i - 1))
                    val variable = LambdaDestructuring(names, field, origin)
                    field.byParameter = variable
                    variables.add(variable)
                } else if (tokens.equals(i, TokenType.NAME)) {
                    variables.add(readLambdaVariable(selfType))
                } else throw NotImplementedError()
                readComma()
            }
        }
        consume("->")
        return variables
    }

    private fun readLambda(selfType: Type?): LambdaExpression {
        val arrow = tokens.findToken(i, "->")
        val variables = if (arrow >= 0) {
            readLambdaVariables(selfType, arrow)
        } else null
        val body = readMethodBody()
        check(currPackage.scopeType == ScopeType.LAMBDA)
        return LambdaExpression(variables, currPackage, body)
    }

    private fun readLambdaVariable(selfType: Type?): LambdaVariable {
        val origin = origin(i)
        val name = tokens.toString(i++)
        val type = readTypeOrNull(selfType)
        return createLambdaVariable(type, name, origin)
    }

    override fun readMethodBody(): ExpressionList {
        val methodScope = currPackage
        val origin = origin(i)
        val result = ArrayList<Expression>()
        if (LOGGER.isDebugEnabled) LOGGER.debug("reading method body[$i], ${tokens.err(i)}")
        if (debug) tokens.printTokensInBlocks(i)
        while (i < tokens.size) {
            val oldSize = result.size
            val oldNumFields = currPackage.fields.size

            fun readDeclarationImpl(isMutable: Boolean, isLateinit: Boolean) {
                val oldScope = currPackage
                val subName = oldScope.generateName("split")
                val newScope = oldScope.getOrPut(subName, ScopeType.METHOD_BODY)
                // field shall be declared in newScope, but expr shall be in old scope...
                val declaration = readFieldInMethod(isMutable, isLateinit, newScope)
                pushScope(newScope) {
                    val remainder = readMethodBody()
                    (remainder.list as ArrayList<Expression>).add(0, declaration)
                    result.add(remainder)
                }
            }

            when {
                tokens.equals(i, TokenType.CLOSE_BLOCK) ->
                    error("} in the middle at ${tokens.err(i)}")

                consumeIf(";") -> {} // skip
                consumeIf("@") -> annotations.add(readAnnotation())
                consumeIf("lateinit") -> {
                    consume("var")
                    readDeclarationImpl(isMutable = true, isLateinit = true)
                    break
                }
                consumeIf("val") || consumeIf("var") -> {
                    // immediately split scope to avoid duplicate fields on a context
                    val isMutable = tokens.equals(i - 1, "var")
                    readDeclarationImpl(isMutable, false)
                    break
                }

                consumeIf("fun") -> readMethod() // will just get added to the scope for later resolution
                consumeIf("inner") -> error("Inner classes inside methods are not supported")
                consumeIf("enum") -> error("Enum classes inside methods are not supported")
                consumeIf("class") -> readClass(ScopeType.NORMAL_CLASS)
                consumeIf("typealias") -> readTypeAlias()

                language == Language.ZAUBER && consumeIf("defer") -> readDefer(result, origin(i - 1))
                language == Language.ZAUBER && consumeIf("errdefer") -> readErrdefer(result, origin(i - 1))

                else -> {
                    result.add(readExpression())
                    if (LOGGER.isDebugEnabled) LOGGER.debug("block += ${result.last()}")
                }
            }

            // todo check whether this works correctly
            // if expression contains assignment of any kind, or a check-call
            //  we must create a new sub-scope,
            //  because the types of our fields may have changed
            if (shouldSplitIntoSubScope(oldSize, oldNumFields, result)) {
                splitIntoSubScope(oldNumFields, result)
            }
        }

        val code = ExpressionList(result, methodScope, origin)
        // methodScope.code.add(code)
        currPackage = methodScope // restore scope
        return code
    }

    /**
     * convert defer into try { remainder } finally { action }
     * */
    private fun readDefer(result: ArrayList<Expression>, origin: Long) {
        // todo constructor calls on values are defers, too
        //  detect them immediately or when flattening the AST?
        val action = readExpression()
        val scope = currPackage
        val subName = scope.generateName("defer", origin)
        val newScope = scope.getOrPut(subName, ScopeType.METHOD_BODY)
        currPackage = newScope
        val remainder = readMethodBody()
        if (remainder.list.isNotEmpty()) {
            val forTryBody = ArrayList(result)
            forTryBody.addAll(remainder.list)
            result.clear()
            result.add(
                TryCatchBlock(
                    ExpressionList(forTryBody, scope, origin),
                    emptyList(),
                    action, scope, origin
                )
            )
        } else {
            result.addAll(remainder.list)
            // can immediately be executed
            result.add(action)
        }
    }

    /**
     * convert errdefer into try { body } catch { action; throw e }
     * */
    private fun readErrdefer(result: ArrayList<Expression>, origin: Long) {
        val action = readExpression()
        val scope = currPackage
        val newScope = scope.generate("errdefer", ScopeType.METHOD_BODY)
        currPackage = newScope
        val errCatch = scope.generate("errdeferHandler", ScopeType.METHOD_BODY) // required for e-parameter
        val remainder = readMethodBody()
        if (remainder.list.isNotEmpty()) {
            val forTryBody = ArrayList(result)
            forTryBody.addAll(remainder.list)
            result.clear()

            val parameter = Parameter(0, "e", ParameterType.VALUE_PARAMETER, Types.Throwable, errCatch, origin)
            val exceptionField = parameter.getOrCreateField(null, Flags.NONE)
            val throwImpl = ThrowExpression(FieldExpression(exceptionField, errCatch, origin), errCatch, origin)

            result.add(
                TryCatchBlock(
                    ExpressionList(forTryBody, scope, origin),
                    listOf(Catch(parameter, ExpressionList(listOf(action, throwImpl), errCatch, origin), origin)),
                    null, scope, origin
                )
            )
        } else {
            // can immediately be executed,
            //  but we don't need it, because we cannot crash on nothing
            result.addAll(remainder.list)
        }
    }

    @Deprecated("Support nested parameters using readForNames()")
    private fun readDestructuringFields(): List<FieldDeclaration> {
        val names = ArrayList<FieldDeclaration>()
        pushCall {
            while (i < tokens.size) {
                val origin = origin(i)
                val name = consumeName(VSCodeType.VARIABLE, 0)
                val type = readTypeOrNull(null)
                names.add(FieldDeclaration(name, type, origin))
                readComma()
            }
        }
        return names
    }

    private fun readDestructuring(isMutable: Boolean, fieldScope: Scope): Expression {
        val names = readForNames()
        val origin = origin(i)
        consume("=")
        val value = readExpression()
        val assignments = ArrayList<Expression>()
        val dstField = createDestructuringAssignments(names, isMutable, assignments, value)
        assignments.add(0, AssignmentExpression(FieldExpression(dstField, fieldScope, origin), value))
        return ExpressionList(assignments, fieldScope, origin)
    }

    private fun readFieldInMethod(
        isMutable: Boolean, isLateinit: Boolean,
        fieldScope: Scope
    ): Expression {
        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            check(!isLateinit) // is immediately assigned -> cannot be lateinit
            return readDestructuring(isMutable, fieldScope)
        }

        val i0 = i
        val origin = origin(i0)
        var afterName = i
        while (afterName < tokens.size) {
            if (tokens.equals(afterName, ":", "=")) break
            else afterName++
        }

        val selfType = if (afterName > i + 1) {
            check(tokens.equals(afterName - 2, ".")) {
                "Expected dot to separate type and name for field at ${tokens.err(i0)}"
            }
            val type = tokens.push(afterName - 2) {
                readTypeNotNull(null, true)
            }
            consume(".")
            check(i == afterName - 1) { "Unused tokens at ${tokens.err(i)}" }
            type
        } else null

        val name = consumeName(VSCodeType.VARIABLE, VSCodeModifier.DECLARATION.flag)
        val keywords = packFlags()

        if (LOGGER.isDebugEnabled) LOGGER.debug("reading var/val $name")

        val type = readTypeOrNull(null)
        val initialValue = if (consumeIf("=")) readExpression() else null
        check(type != null || initialValue != null) { "Field at ${tokens.err(i0)} either needs a type or a value" }

        // define variable in the scope
        val field = fieldScope.addField(
            selfType, selfType != null, isMutable = isMutable, null,
            name, type, initialValue, keywords, origin
        )

        return createDeclarationExpression(fieldScope, initialValue, field)
    }
}