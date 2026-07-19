package me.anno.zauber.ast.rich.parser

import me.anno.langserver.VSCodeModifier
import me.anno.langserver.VSCodeType
import me.anno.support.Language
import me.anno.utils.NumberUtils.toInt
import me.anno.utils.StringStyles.ORANGE
import me.anno.utils.StringStyles.style
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.FlagSet
import me.anno.zauber.ast.rich.Annotation
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.TokenListIndex.mergeOrigins
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.resolved.SuperExpression
import me.anno.zauber.ast.rich.expression.unresolved.AssignmentExpression
import me.anno.zauber.ast.rich.expression.unresolved.FieldExpression
import me.anno.zauber.ast.rich.expression.unresolved.SuperCallExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.FieldGetterSetter.createDelegateGetter
import me.anno.zauber.ast.rich.member.FieldGetterSetter.createDelegateSetter
import me.anno.zauber.ast.rich.member.FieldGetterSetter.createGetterMethod0
import me.anno.zauber.ast.rich.member.FieldGetterSetter.createSetterMethod0
import me.anno.zauber.ast.rich.member.FieldGetterSetter.createValueField
import me.anno.zauber.ast.rich.member.FieldGetterSetter.finishField
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.createAssignmentInstructionsForPrimaryConstructor
import me.anno.zauber.ast.rich.parameter.*
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.scope.lazy.LazyExpression
import me.anno.zauber.scope.lazy.TokenSubList
import me.anno.zauber.tokenizer.TokenList
import me.anno.zauber.tokenizer.TokenType
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.TypeOfExpr
import kotlin.math.max

/**
 * to make type-resolution immediately available/resolvable
 * */
abstract class ASTClassScanner(tokens: TokenList, language: Language) :
    ZauberASTBuilderBase(tokens, root, true, language) {

    companion object {
        private val classPrefixes = arrayOf("data", "enum", "value", "inner")
        private val notValueKeywords = arrayOf(
            "fun", "val", "var", "lateinit", "const",
            "public", "private", "protected", "interface",
            "package", "import", "companion",
            "open", "abstract", "override", "operator",
            "typealias", "external",
            "constructor"
        )
    }

    open fun skipAnnotations() {
        while (consumeIf("@")) {
            if (readType(null, true) != null) {
                skipValueParameters()
            } else {
                i--
                return
            }
        }
    }

    fun pushNamedScopeLazy(
        name: String,
        listenType: Int,
        scopeType: ScopeType,
        readLazily: (scope: Scope, readBody: Boolean) -> Unit
    ) {
        val i0 = i
        val i1 = tokens.size
        val parentScope = currPackage

        val classScope = parentScope.getOrPut(name, scopeType)
        classScope.addFlags(listenType or packFlags())
        classScope.fileName = tokens.fileName
        classScope.annotations.addAll(annotations); annotations.clear()

        classScope.addInitPart(ScopeInitType.DISCOVER_MEMBERS) {

            // store old state
            val prevPackage = currPackage
            val prevSize = tokens.size
            val prevI = i

            // set original state
            i = i0
            tokens.size = i1
            currPackage = parentScope

            readLazily(classScope, true)

            // restore state
            currPackage = prevPackage
            tokens.size = prevSize
            i = prevI
        }

        readLazily(classScope, false)
    }

    open fun readNamedScope(name: String, listenType: FlagSet, scopeType: ScopeType) {
        val origin = origin(i - 1)
        pushNamedScopeLazy(name, listenType, scopeType) { classScope, readBody ->
            readTypeParameterDeclarations(classScope, true)

            if (consumeIf("private")) {
                addFlag(Flags.PRIVATE)
                consume("constructor")
            } else if (consumeIf("protected")) {
                addFlag(Flags.PROTECTED)
                consume("constructor")
            } else consumeIf("constructor")

            if (readBody) {
                val constrOrigin = origin(i)
                val constructorScope = classScope.getOrCreatePrimaryConstructorScope()
                constructorScope.addFlags(packFlags())
                pushScope(constructorScope) {
                    val selfType = classScope.typeWithArgs
                    val extra = getSyntheticParameters(classScope, constructorScope, constrOrigin)
                    val valueParameters = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                        readParameterDeclarations(selfType, extra, ParameterType.VALUE_PARAMETER)
                    } else extra

                    if (classScope.flags.hasFlag(Flags.VALUE)) {
                        validateValueClassParameters(
                            name, valueParameters,
                            classScope.flags.hasFlag(Flags.ANNOTATION)
                        )
                    }

                    val instr = createAssignmentInstructionsForPrimaryConstructor(classScope, valueParameters, origin)
                    constructorScope.selfAsConstructor = Constructor(
                        valueParameters, constructorScope,
                        null, instr, flags, constrOrigin
                    )
                }
            } else if (tokens.equals(i, TokenType.OPEN_CALL)) {
                // skip constructor params
                skipCall()
                packFlags()
            }

            readSuperCalls(classScope, readBody)

            if (readBody) {
                addAnySuperCall(classScope)
            }

            if (scopeType == ScopeType.OBJECT || scopeType == ScopeType.COMPANION_OBJECT) {
                classScope.getOrCreateObjectField(origin)
            }

            readClassBody(classScope, readBody)
            popGenericParams()
        }
    }

    fun addAnySuperCall(classScope: Scope) {
        if (classScope.superCalls.none { it.isClassCall } && classScope != Types.Any.clazz && !classScope.isInterface()) {
            val origin = origin(i - 1) // fine?
            classScope.superCalls.add(SuperCall(Types.Any, emptyList(), null, origin))
        }
    }

    open fun readClassBody(classScope: Scope, readBody: Boolean) {
        if (readBody) readClassBody(classScope)
        else if (tokens.equals(i, TokenType.OPEN_BLOCK)) skipBlock()
    }

    override fun readSuperCalls(classScope: Scope, readBody: Boolean) {
        if (consumeIf(":")) {
            readSuperCallsImpl(classScope, readBody)
        }

        addAnySuperCallIfNoneIsProvided(classScope, readBody)
    }

    fun readSuperCallsImpl(classScope: Scope, readBody: Boolean) {
        val primConstrScope = classScope.getOrCreatePrimaryConstructorScope()
        pushScope(primConstrScope) {
            do {
                val origin = origin(i)
                val type = readTypeNotNull(classScope.typeWithArgs, true)
                val valueParameters = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    if (readBody) readValueParameters() else {
                        skipValueParameters()
                        null
                    }
                } else null
                val delegate = if (consumeIf("by")) {
                    if (readBody) readLazyValue(false)
                    else skipLazyValue(false)
                } else null

                if (readBody) {
                    classScope.superCalls.add(SuperCall(type, valueParameters, delegate, origin))
                    if (valueParameters != null) {
                        val constructor = primConstrScope.selfAsConstructor!!
                        constructor.superCall = InnerSuperCall(InnerSuperCallTarget.SUPER, valueParameters, origin)
                    }
                }
            } while (consumeIf(TokenType.COMMA))
        }
    }

    @Deprecated("Call readSuperCalls directly")
    fun collectSuperCalls(classScope: Scope, readBody: Boolean) {
        return readSuperCalls(classScope, readBody)
    }

    fun skipValueParameters() {
        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            // skip constructor params
            i = tokens.findBlockEnd(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL) + 1
        }
    }

    fun skipBlock() {
        if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            // skip constructor params
            i = tokens.findBlockEnd(i, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK) + 1
        }
    }

    override fun readFileLevel() {
        while (i < tokens.size) {
            val i0 = i
            when (tokens.getType(i)) {
                TokenType.OPEN_BLOCK, TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.INDENT,
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK, TokenType.DEDENT ->
                    error("Unexpected token ${tokens.err(i)}")
                else -> readNamed()
            }
            i = max(i0 + 1, i)
        }
    }

    open fun readPackage() {
        val (path, ni) = tokens.readPath(i, ScopeType.PACKAGE)
        currPackage = path
        i = ni
    }

    open fun readImport() {
        val (path, ni) = tokens.readImport(i)
        imports.add(path)
        i = ni
    }

    open fun checkImports(): Boolean {
        throw NotImplementedError()
    }

    open fun readNamed() {
        // to do switch is probably faster...
        when {
            consumeIf("package") -> readPackage()
            consumeIf("import") -> readImport()
            consumeIf("typealias") -> readTypeAlias()
            consumeIf("var") || consumeIf("val") -> readField()
            consumeIf("fun") -> {
                if (consumeIf("interface")) {
                    val name = consumeName(VSCodeType.INTERFACE, VSCodeModifier.DECLARATION.flag)
                    readNamedScope(name, Flags.FUN_INTERFACE, ScopeType.INTERFACE)
                } else readMethod()
            }
            consumeIf("macro") -> {
                addFlag(Flags.MACRO)
                readMethod()
            }
            consumeIf("constructor") -> readConstructor()
            consumeIf("external") -> addFlag(Flags.EXTERNAL)
            consumeIf("override") -> addFlag(Flags.OVERRIDE)
            consumeIf("public") -> addFlag(Flags.PUBLIC)
            consumeIf("protected") -> addFlag(Flags.PROTECTED)
            consumeIf("private") -> addFlag(Flags.PRIVATE)
            consumeIf("abstract") -> addFlag(Flags.ABSTRACT)
            consumeIf("internal") -> addFlag(Flags.INTERNAL)
            consumeIf("operator") -> addFlag(Flags.OPERATOR)
            consumeIf("open") -> addFlag(Flags.OPEN)
            consumeIf("final") -> addFlag(Flags.FINAL)
            consumeIf("sealed") -> addFlag(Flags.SEALED)
            consumeIf("tailrec") -> {}// addKeyword(Keywords.TAILREC)
            consumeIf("lateinit") -> addFlag(Flags.LATEINIT)
            consumeIf("inline") -> addFlag(Flags.INLINE)
            consumeIf("crossinline") -> addFlag(Flags.CROSS_INLINE)
            consumeIf("const") -> {
                addFlag(Flags.CONSTEXPR)
                consumeIf("val") // val is optional
                readField()
            }
            consumeIf("infix") -> addFlag(Flags.INFIX)
            consumeIf("external") -> addFlag(Flags.EXTERNAL)
            consumeIf("implicit") -> addFlag(Flags.IMPLICIT)
            consumeIf(";") -> {}
            consumeIf("init") -> {
                currPackage.primaryConstructorScope!!
                    .code.add(readLazyBody())
            }

            consumeIf("enum") -> {
                consume("class")
                val name = consumeName(VSCodeType.ENUM, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.ENUM_CLASS)
            }

            consumeIf("inner") -> {
                consume("class")
                val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.INNER_CLASS)
            }

            consumeIf("data") -> {
                consume("class")
                val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.DATA_CLASS, ScopeType.NORMAL_CLASS)
            }

            consumeIf("value") -> {
                when {
                    consumeIf("class") -> {
                        val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                        readNamedScope(name, Flags.VALUE, ScopeType.NORMAL_CLASS)
                    }
                    consumeIf("val") -> {
                        addFlag(Flags.VALUE)
                        readField()
                    }
                    consumeIf("var") -> {
                        addFlag(Flags.VALUE)
                        readField()
                    }
                    else -> error("Expected class, val or var after 'value' at ${tokens.err(i)}")
                }
            }

            consumeIf("class") -> {
                check(!tokens.equals(i - 2, "::"))
                val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.NORMAL_CLASS)
            }

            tokens.equals(i, "annotation") && tokens.equals(i + 1, "class") -> {
                addFlag(Flags.ANNOTATION); i += 2
                addFlag(Flags.VALUE) // annotation classes are comp-time, so they are value classes
                val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.NORMAL_CLASS)
            }

            tokens.equals(i, "object") && !tokens.equals(i - 1, "companion")
                    && !tokens.equals(i + 1, ":") -> {
                i++ // skip 'object'
                val name = consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.OBJECT)
            }

            consumeIf("companion") -> {
                consume("object")
                val name = if (tokens.equals(i, TokenType.NAME, TokenType.KEYWORD)) {
                    consumeName(VSCodeType.CLASS, VSCodeModifier.DECLARATION.flag)
                } else "Companion"
                readNamedScope(name, Flags.NONE, ScopeType.COMPANION_OBJECT)
            }

            consumeIf("interface") -> {
                val name = consumeName(VSCodeType.INTERFACE, VSCodeModifier.DECLARATION.flag)
                val keywords = if (tokens.equals(i - 2, "fun")) Flags.FUN_INTERFACE else Flags.NONE
                readNamedScope(name, keywords, ScopeType.INTERFACE)
            }

            consumeIf("typealias") -> {
                val name = consumeName(VSCodeType.TYPE, VSCodeModifier.DECLARATION.flag)
                readNamedScope(name, Flags.NONE, ScopeType.TYPE_ALIAS)
            }

            consumeIf("@") -> {
                val scope = readAnnotationScope()
                val type = readTypeNotNull(null, true)
                val valueParameters = if (tokens.equals(i, TokenType.OPEN_CALL)) readValueParameters() else emptyList()
                annotations.add(Annotation(type, valueParameters, scope))
            }

            else -> error("Unknown token ${tokens.err(i)}")
        }
    }

    open fun readSelfTypeIfPresent(end: Int): Type? {
        val nameStart = i
        check(tokens.equals(end - 1, TokenType.NAME, TokenType.KEYWORD)) {
            "Expected name at ${tokens.err(end - 1)}"
        }
        check(end > nameStart) { "Expected name at ${tokens.err(nameStart)}" }
        return if (nameStart < end - 1) readSelfType(end) else null
    }

    open fun readField() {
        val i0 = i - 1
        val origin = origin(i0)
        val isMutable = tokens.equals(i0, "var")
        val isConst = flags.hasFlag(Flags.CONSTEXPR)
        if (isConst) {
            check(currPackage.isObjectLike()) {
                // we only allow constants in object-likes, so we can compute all of them at comptime
                "${style("const", ORANGE)} fields are only supported in object-likes " +
                        "(${style("object", ORANGE)}, " +
                        "${style("companion object", ORANGE)}, " +
                        "${style("package", ORANGE)})\n  at ${tokens.err(i - 1)}"
            }
        }

        val end = findFieldNameEnd()
        val name = tokens.toString(end - 1)

        // println("Reading field $name in $currPackage")

        val ownerScope = currPackage
        val flags = packFlags()

        val typeParameters = readTypeParameterDeclarations(ownerScope, false)

        val selfType0 = readSelfTypeIfPresent(end)
        val selfType = selfType0 ?: ownerScope.selfType

        val fieldName = consumeName(VSCodeType.PROPERTY, VSCodeModifier.DECLARATION.flag)
        check(name == fieldName) { "Expected same name, got mismatch: $name vs $fieldName at ${tokens.err(i - 1)}" }

        var valueType = if (consumeIf(":")) readTypeNotNull(selfType, true) else null
        var delegateExpr: Expression? = null

        val initialValue = pushScope(ownerScope.getOrCreatePrimaryConstructorScope()) {
            when {
                consumeIf("=") -> readLazyValue(true)
                consumeIf("by") -> {
                    delegateExpr = readLazyValue(true)
                    null
                }
                flags.hasFlag(Flags.LATEINIT) -> SpecialValueExpression(SpecialValue.NULL, ownerScope, origin)
                else -> null
            }
        }

        if (isConst) {
            check(initialValue != null) {
                "Const field ${ownerScope.pathStr}.$name must have initial value at ${resolveOrigin(origin)}"
            }
        }

        val getterVisibility = readVisibility()
        var setterVisibility = getterVisibility
        var getterOrigin = origin
        var getterBody: Expression? = if (consumeIf("get")) {
            getterOrigin = origin(i - 1)
            val body = if (consumeIf(TokenType.OPEN_CALL)) {
                consume(TokenType.CLOSE_CALL)
                readBodyForField(fieldName, ScopeType.FIELD_GETTER)
            } else null
            setterVisibility = readVisibility()
            body
        } else null

        var setterOrigin = origin
        lateinit var setterName: String
        var setterBody: Expression? = if (consumeIf("set")) {
            setterOrigin = origin(i - 1)
            if (consumeIf(TokenType.OPEN_CALL)) {
                setterName = consumeName(VSCodeType.PARAMETER, VSCodeModifier.DECLARATION.flag)
                val setterType = if (consumeIf(":")) {
                    readTypeNotNull(null, true)
                } else null
                if (valueType == null) valueType = setterType

                consume(TokenType.CLOSE_CALL)
                readBodyForField(fieldName, ScopeType.FIELD_SETTER)
            } else null
        } else null

        if (valueType == null && getterBody != null) {
            valueType = TypeOfExpr(getterBody)
        }

        val field = ownerScope.addField(
            selfType0, selfType0 != null, isMutable, null,
            name, valueType, initialValue, flags, origin
        )
        field.annotations.addAll(annotations); annotations.clear()
        field.typeParameters = typeParameters

        if (getterBody != null && delegateExpr != null) {
            error("Cannot have both getter and delegate at ${tokens.err(i0)}")
        }

        if (initialValue != null && delegateExpr != null) {
            error("Cannot have both initial value and delegate at ${tokens.err(i0)}")
        }

        if (delegateExpr != null) {

            val initial = delegateExpr
            val delegateField = ownerScope.createImmutableField(initial, "delegate", initial.origin)
            val backingFieldExpr = FieldExpression(delegateField, ownerScope, origin)
            ownerScope.getOrCreatePrimaryConstructorScope()
                .code.add(AssignmentExpression(backingFieldExpr, initial))

            getterBody = pushScope(ScopeType.FIELD_GETTER, "delegateGetter") { getterScope ->
                val getterExpr = createDelegateGetter(getterScope, backingFieldExpr, origin)
                ReturnExpression(getterExpr, null, getterScope, origin)
            }

            if (isMutable && setterBody == null) {
                setterName = "value"
                pushScope(ScopeType.FIELD_SETTER, "delegateSetter") { setterScope ->
                    val valueField = createValueField(field, setterName, setterScope, origin)
                    val valueExpr = FieldExpression(valueField, setterScope, origin)
                    setterBody = createDelegateSetter(setterScope, backingFieldExpr, valueExpr, origin)
                }
            }
        }

        if (initialValue != null) {
            val constr = ownerScope.getOrCreatePrimaryConstructorScope()
            val fieldExpr = FieldExpression(field, ownerScope, origin)
            constr.code.add(AssignmentExpression(fieldExpr, initialValue))
        }

        if (getterBody != null) createGetterMethod0(field, getterBody, getterBody.scope, getterOrigin)
        if (setterBody != null) createSetterMethod0(field, setterBody, setterName, setterBody.scope, setterOrigin)
        finishField(ownerScope, field)

        field.getter?.addFlags(getterVisibility)
        field.setter?.addFlags(setterVisibility)
        popGenericParams()
    }

    private fun readBodyForField(fieldName: String, scopeType: ScopeType): Expression {
        return pushScope(scopeType, fieldName) { newScope ->
            if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                readLazyBody()
            } else if (consumeIf("=")) {
                val originI = origin(i - 1)
                ReturnExpression(readLazyValue(forField = true), null, newScope, originI)
            } else error("Expected body for getter, got neither = nor { at ${tokens.err(i)}")
        }
    }

    private fun readVisibility(): Int {
        var flags = 0
        while (i < tokens.size) {
            when {
                consumeIf("public") -> {}
                consumeIf("private") -> flags = flags or Flags.PRIVATE
                consumeIf("protected") -> flags = flags or Flags.PROTECTED
                else -> return flags
            }
        }
        return flags
    }

    private fun findFieldNameEnd(): Int {
        // val x, var A<>.x
        // val x: Int, val x = 0
        //  end symbols: [:, =, get(), set(), public, private, protected]
        var end = i
        var depth = 0
        findFieldEnd@ while (end < tokens.size) {
            val j0 = end++
            when (tokens.getType(j0)) {
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK, TokenType.INDENT -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK, TokenType.DEDENT -> depth--
                else -> when {
                    tokens.equals(j0, "<") -> depth++
                    tokens.equals(j0, ">") -> depth--
                    tokens.equals(j0, ":", "=", "get", "set", "public", "private", "protected", "by") -> {
                        if (depth == 0) return j0
                    }
                }
            }
            check(depth >= 0) { "Invalid depth @${tokens.err(i)}" }
        }

        error("Missing field end at ${tokens.err(i)}")
    }

    open fun readConstructor() {
        val origin = origin(i - 1)
        val classScope = currPackage[ScopeInitType.AFTER_DISCOVERY]
        val constrScope = classScope.generate("constructor", origin, ScopeType.CONSTRUCTOR)
        constrScope.setEmptyTypeParams()
        constrScope.flags = constrScope.flags or packFlags()
        constrScope.annotations.addAll(annotations); annotations.clear()

        pushScope(constrScope) {
            val selfType = classScope.typeWithArgs
            val extra = getSyntheticParameters(classScope, constrScope, origin)
            val valueParameters = readParameterDeclarations(selfType, extra, ParameterType.VALUE_PARAMETER)
            val superCall = if (consumeIf(":")) readInnerSuperCall() else null

            val body =
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) readLazyBody()
                else ExpressionList(emptyList(), constrScope, origin)

            constrScope.selfAsConstructor = Constructor(
                valueParameters,
                constrScope, superCall, body,
                constrScope.flags, origin
            )
        }
    }

    private fun readInnerSuperCall(): InnerSuperCall {
        val origin = origin(i)
        val type = when {
            consumeIf("this") -> InnerSuperCallTarget.THIS
            consumeIf("super") -> InnerSuperCallTarget.SUPER
            else -> error("Expected this() or super() at ${tokens.err(i)}")
        }
        val values = readValueParameters()
        return InnerSuperCall(type, values, origin)
    }

    open fun readMethod() {

        // todo use start of keywords instead
        val originOnFun = origin(i - 1)

        val end = findParameterStart()
        val name = tokens.toString(end - 1)
        val ownerScope = currPackage
        if (ownerScope.isInterface()) addFlag(Flags.OPEN)

        val methodScope = ownerScope.generate(name, originOnFun, ScopeType.METHOD)
        methodScope.addFlags(packFlags())
        methodScope.annotations.addAll(annotations); annotations.clear()

        pushScope(methodScope) {
            val typeParameters = readTypeParameterDeclarations(methodScope, true)

            val selfType0 = readSelfTypeIfPresent(end)
            val selfType = selfType0 ?: ownerScope.selfType

            val name = consumeName(VSCodeType.METHOD, VSCodeModifier.DECLARATION.flag)

            val valueParameters = readParameterDeclarations(selfType, emptyList(), ParameterType.VALUE_PARAMETER)
            val whereConditions = readWhereConditions()

            val returnType = if (consumeIf(":")) {
                readTypeNotNull(selfType, true)
            } else if (tokens.equals(i, "{") ||
                methodScope.flags.hasFlag(Flags.EXTERNAL)
            ) { // type is implicitly Unit
                Types.Unit
            } else null

            val originBeforeBody = origin(i - 1)

            val body = when {
                tokens.equals(i, TokenType.OPEN_BLOCK) -> readLazyBody()
                consumeIf("=") -> {
                    val originI = origin(i - 1)
                    ReturnExpression(readLazyValue(false), null, methodScope, originI)
                }
                else -> null
            }

            val method = Method(
                selfType0, selfType0 != null, name,
                typeParameters, valueParameters,
                methodScope, returnType, whereConditions, body,
                methodScope.flags, mergeOrigins(originOnFun, originBeforeBody)
            )
            methodScope.selfAsMethod = method

            popGenericParams()
        }
    }

    fun readLazyBody(): Expression {
        return pushBlock(ScopeType.METHOD_BODY, "body") { scope ->
            val tokens1 = TokenSubList(tokens, i, tokens.size)
            val expr = LazyExpression(tokens1, true, scope, origin(i), imports, generics)
            // load expression contents, if we need them
            scope.addInitPart(ScopeInitType.RESOLVE_METHOD_BODY) { expr.value }
            i = tokens.size
            expr
        }
    }

    fun readLazyValue(forField: Boolean): Expression {
        check(i < tokens.size) { "Cannot read lazy-value at the end, ${tokens.err(i)}" }
        val end = findLazyValueEnd(forField)
        check(i < end) { "Lazy value must not be empty, @${tokens.err(i)}" }
        if (false) println("End for lazy value: ${tokens.err(end)}")
        return pushScope(ScopeType.METHOD_BODY, "body") { scope ->
            val tokens1 = TokenSubList(tokens, i, end)
            val expr = LazyExpression(tokens1, false, scope, origin(i), imports, generics)
            // load expression contents, if we need them
            scope.addInitPart(ScopeInitType.RESOLVE_METHOD_BODY) { expr.value }
            i = end
            expr
        }
    }

    fun skipLazyValue(forField: Boolean): Expression? {
        check(i < tokens.size) { "Cannot read lazy-value at the end, ${tokens.err(i)}" }
        val end = findLazyValueEnd(forField)
        check(i < end) { "Lazy value must not be empty, @${tokens.err(i)}" }
        i = end
        return null
    }

    private fun findLazyValueEnd(forField: Boolean): Int {
        var end = i
        var depth = 0
        var softDepth = 0
        searchEnd@ while (end < tokens.size) {
            val j0 = end++
            when (tokens.getType(j0)) {
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> {
                    if (depth == 0) return end
                    depth--
                }
                TokenType.COMMA -> if (depth == 0) {
                    // todo this scenario is ambiguous, try the best we can...
                    if (softDepth <= 0) return j0
                }
                TokenType.SEMICOLON -> if (depth == 0) return j0
                else -> if (depth == 0) when {
                    tokens.equals(j0, ".", "+", "-", "*", "/", "%", "&&", "||") &&
                            tokens.equals(j0, TokenType.NAME, TokenType.KEYWORD) -> end++ // skip another one
                    tokens.equals(j0, *notValueKeywords) -> return j0
                    forField && j0 > i && !tokens.equals(j0 - 1, ".") &&
                            tokens.equals(j0, "get", "set") -> return j0
                    tokens.equals(j0, "object") && !tokens.equals(j0, ":") -> return j0
                    // enum class, data class, private class... these depend on the work after them...
                    tokens.equals(j0 + 1, "class") && tokens.equals(j0, *classPrefixes) -> return j0
                    tokens.equals(j0, "class") && !tokens.equals(j0 - 1, "::") -> return j0
                    tokens.equals(j0, "<") -> softDepth++
                    tokens.equals(j0, ">") -> softDepth--
                }
            }
        }
        return tokens.size
    }

    private fun readSelfType(end: Int): Type {
        check(tokens.equals(end - 2, ".", "?.")) {
            "Expected period for field with receiver type at ${tokens.err(end - 2)}"
        }

        val type = tokens.push(end - 2) {
            readTypeNotNull(null, true)
        }

        if (!consumeIf("?.")) consume(".")
        return type
    }

    private fun findParameterStart(): Int {
        // val x, var A<>.x
        // val x: Int, val x = 0
        //  end symbols: [:, =, get(), set(), public, private, protected]
        var end = i
        var depth = 0
        findFieldEnd@ while (end < tokens.size) {
            val j0 = end++
            when (tokens.getType(j0)) {
                TokenType.OPEN_CALL -> {
                    if (depth == 0) {
                        end--
                        break@findFieldEnd
                    } else depth++
                }
                TokenType.OPEN_ARRAY, TokenType.OPEN_BLOCK -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_ARRAY, TokenType.CLOSE_BLOCK -> depth--
                else -> when {
                    tokens.equals(j0, "<") -> depth++
                    tokens.equals(j0, ">") -> depth--
                }
            }
            check(depth >= 0) { "Invalid depth @${tokens.err(i)}" }
        }
        return end
    }

    override fun readExpression(minPrecedence: Int): Expression = readLazyValue(false)

    override fun readBodyOrExpression(label: String?): Expression {
        throw NotImplementedError()
    }

    override fun readAnnotation(): Annotation {
        throw NotImplementedError()
    }

    override fun readParameterDeclarations(
        selfType: Type?,
        extra: List<Parameter>,
        parameterType: ParameterType
    ): List<Parameter> {
        val parameters = ArrayList<Parameter>(extra)
        pushCall {
            while (i < tokens.size) {
                // todo comptime name: type
                var flags = Flags.NONE

                while (true) {
                    flags = flags or when {
                        consumeIf("public") -> Flags.PUBLIC
                        consumeIf("protected") -> Flags.PROTECTED
                        consumeIf("private") -> Flags.PRIVATE
                        consumeIf("open") -> Flags.OPEN
                        consumeIf("override") -> Flags.OVERRIDE
                        else -> break
                    }
                }

                val i0 = i
                val paramOrigin = origin(i)
                val isConst = consumeIf("const")
                val isVararg = consumeIf("vararg")
                val isVal = consumeIf("val")
                val isVar = consumeIf("var")

                check(isConst.toInt() + isVar.toInt() + isVal.toInt() <= 1) {
                    "Only one of 'var', 'val' and 'const' must be present at ${tokens.err(i0)}"
                }

                val name = consumeName(VSCodeType.PARAMETER, 0)
                consume(":")

                var type = readTypeNotNull(selfType, true)

                val defaultValue =
                    if (consumeIf("=")) readLazyValue(false)
                    else null

                if (isVararg) type = Types.Array.withTypeParameter(type)
                val parameter = Parameter(
                    parameters.size,
                    when {
                        isVar -> ParameterMutability.VAR
                        isVal -> ParameterMutability.VAL
                        isConst -> ParameterMutability.CONST
                        else -> ParameterMutability.DEFAULT
                    },
                    if (isVararg) ParameterExpansion.VARARG else ParameterExpansion.NONE,
                    parameterType, name, type, defaultValue, currPackage, paramOrigin
                )
                parameters.add(parameter)

                val size = tokens.size
                parameter.getOrCreateField(null, flags)
                check(size == tokens.size) { "Token size changed" }

                readComma()
            }
        }
        return parameters
    }

    override fun readMethodBody(): ExpressionList {
        throw NotImplementedError()
    }
}