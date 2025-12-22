package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.astbuilder.expression.constants.StringExpression
import me.anno.zauberei.astbuilder.flow.*
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.typeresolution.TypeResolution.getSelfType
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.Types.NullableAnyType
import me.anno.zauberei.types.Types.UnitType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.LambdaType
import me.anno.zauberei.types.impl.NullType.typeOrNull
import kotlin.math.max
import kotlin.math.min

// I want macros... how could we implement them? learn about Rust macros
//  -> we get tokens as attributes in a specific pattern,
//  and after resolving the pattern, we can copy-paste these pattern variables as we please

//  -> we should be able to implement when() and for() using these

// todo getting started quickly is important for my Dopamine levels!

// todo first parse class structures, function contents later, so we can immediately fill in what a variable name belongs to (type, local, class member, ...)

class ASTBuilder(val tokens: TokenList, val root: Scope) {

    companion object {
        val fileLevelKeywords = listOf(
            "enum", "private", "protected", "fun", "class", "data", "value",
            "companion", "object", "constructor", "inline",
            "override", "abstract", "open", "final", "operator",
            "const", "lateinit", "annotation", "internal", "inner", "sealed",
            "infix", "external"
        )

        val paramLevelKeywords = listOf(
            "private", "protected", "var", "val", "open", "override",
            "crossinline", "vararg", "final"
        )

        val supportedInfixFunctions = listOf(
            // these shall only be supported for legacy reasons: I dislike that their order of precedence isn't clear
            "shl", "shr", "ushr", "and", "or", "xor",

            // I like these:
            "in", "to", "step", "until", "downTo",
            "is", "!is", "as", "as?"
        )

        var debug = false
    }

    val imports = ArrayList<Import>()
    val keywords = ArrayList<String>()
    val genericParams = ArrayList<HashMap<String, GenericType>>()

    init {
        genericParams.add(HashMap())
    }

    // todo assign them appropriately
    val annotations = ArrayList<Annotation>()

    var currPackage = root
    var i = 0

    fun readPath(): Scope {
        check(tokens.equals(i, TokenType.NAME))
        var path = root.getOrPut(tokens.toString(i++), null)
        while (tokens.equals(i, ".") && tokens.equals(i + 1, TokenType.NAME)) {
            path = path.getOrPut(tokens.toString(i + 1), null)
            i += 2 // skip period and name
        }
        return path
    }

    fun readTypePath(): Type {
        check(tokens.equals(i, TokenType.NAME))
        val name0 = tokens.toString(i++)
        var path = genericParams.last()[name0]
            ?: currPackage.resolveType(name0, this)
        while (tokens.equals(i, ".") && tokens.equals(i + 1, TokenType.NAME)) {
            path = (path as ClassType).clazz.getOrPut(tokens.toString(i + 1), null).typeWithoutArgs
            i += 2 // skip period and name
        }
        return path
    }

    private fun readClass() {
        check(tokens.equals(++i, TokenType.NAME))
        val name = tokens.toString(i++)
        val clazz = currPackage.getOrPut(name, tokens.fileName, null)
        val keywords = packKeywords()
        val typeParameters = readTypeParameterDeclarations(clazz)
        clazz.typeParameters = typeParameters
        clazz.hasTypeParameters = true

        val privatePrimaryConstructor = tokens.equals(i, "private")
        if (privatePrimaryConstructor) i++

        readAnnotations()

        val scopeType =
            if ("enum" in keywords) ScopeType.ENUM_CLASS
            else ScopeType.NORMAL_CLASS

        if (tokens.equals(i, "constructor")) i++
        val constructorOrigin = origin(i)
        val constructorParams = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            pushScope(clazz.getOrCreatePrimConstructorScope()) {
                val selfType = ClassType(clazz, null)
                pushCall { readParamDeclarations(selfType) }
            }
        } else null

        val needsSuperCall = clazz != AnyType.clazz
        readSuperCalls(clazz, needsSuperCall)

        if (constructorParams != null) {
            val prim = clazz.getOrCreatePrimConstructorScope()
            for (field in prim.fields) {
                clazz.addField(field)
            }
        }

        val primaryConstructor = Constructor(
            clazz, constructorParams ?: emptyList(),
            clazz.getOrCreatePrimConstructorScope(), null, null,
            if (privatePrimaryConstructor) listOf("private") else emptyList(),
            constructorOrigin
        )
        clazz.constructors.add(primaryConstructor)

        readClassBody(name, keywords, scopeType)
        popGenericParams()
    }

    private inline fun <R> pushScope(name: String, scopeType: ScopeType, callback: (Scope) -> R): R {
        val parent = currPackage
        val child = parent.getOrPut(name, scopeType)
        currPackage = child
        val value = callback(child)
        currPackage = parent
        return value
    }

    private inline fun <R> pushScope(scope: Scope, callback: () -> R): R {
        val parent = currPackage
        currPackage = scope
        val value = callback()
        currPackage = parent
        return value
    }

    private fun readInterface() {
        keywords.add("interface")
        check(tokens.equals(++i, TokenType.NAME))
        val name = tokens.toString(i++)
        val clazz = currPackage.getOrPut(name, tokens.fileName, ScopeType.INTERFACE)
        val keywords = packKeywords()
        clazz.typeParameters = readTypeParameterDeclarations(clazz)
        clazz.hasTypeParameters = true

        readSuperCalls(clazz, false)
        readClassBody(name, keywords, ScopeType.INTERFACE)
        popGenericParams()
    }

    private fun readAnnotations() {
        if (tokens.equals(i, "@")) {
            annotations.add(readAnnotation())
        }
    }

    private fun readObject() {
        val origin = origin(i)
        val name = if (tokens.equals(++i, TokenType.NAME)) {
            tokens.toString(i++)
        } else if (keywords.remove("companion")) {
            "Companion"
        } else throw IllegalStateException("Missing object name")
        keywords.add("object")
        val keywords = packKeywords()

        val scope = currPackage.getOrPut(name, tokens.fileName, ScopeType.OBJECT)
        readSuperCalls(scope, true)
        readClassBody(name, keywords, ScopeType.OBJECT)

        scope.hasTypeParameters = true // no type-params are supported
        scope.objectField = Field(
            scope, false, true, null,
            "__instance__",
            ClassType(scope, emptyList()),
            /* todo should we set initialValue? */ null, emptyList(), origin
        )
    }

    private fun readSuperCalls(clazz: Scope, needsEntry: Boolean) {
        if (tokens.equals(i, ":")) {
            i++ // skip :
            var endIndex = findEndOfSuperCalls(i)
            if (endIndex < 0) endIndex = tokens.size
            push(endIndex) {
                while (i < tokens.size) {
                    clazz.superCalls.add(readSuperCall())
                    readComma()
                }
            }
            i = endIndex // index of {
        }
        if (needsEntry && clazz.superCalls.isEmpty()) {
            clazz.superCalls.add(SuperCall(AnyType, emptyList(), null))
        }
    }

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

    private fun readClassBody(name: String, keywords: List<String>, scopeType: ScopeType) {
        currPackage.getOrPut(name, tokens.fileName, scopeType).keywords.addAll(keywords)
        if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
            pushBlock(scopeType, name) {
                if ("enum" in keywords) {
                    val endIndex = readEnumBody()
                    i = min(endIndex + 1, tokens.size) // skipping over semicolon
                }
                readFileLevel()
            }
        }
    }

    private fun readEnumBody(): Int {
        var endIndex = tokens.findToken(i, ";")
        if (endIndex < 0) endIndex = tokens.size
        push(endIndex) {
            while (i < tokens.size) {
                // read enum value
                readAnnotations()
                check(tokens.equals(i, TokenType.NAME))
                val origin = origin(i)
                val name = tokens.toString(i++)
                val typeParams = readTypeParams()
                val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                    pushCall { readParamExpressions() }
                } else emptyList()
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    readClassBody(name, emptyList(), ScopeType.ENUM_ENTRY_CLASS)
                }

                currPackage.enumValues.add(EnumEntry(name, typeParams, params, origin))
                readComma()
            }
        }
        return endIndex
    }

    var lastField: Field? = null

    private fun readVarValInClass(isVar: Boolean) {
        i++
        check(tokens.equals(i, TokenType.NAME))
        var ownerType: Type? = null
        val origin = origin(i)
        var name = tokens.toString(i++)
        val keywords = packKeywords()

        if (tokens.equals(i, ".")) {
            check(tokens.equals(++i, TokenType.NAME))
            ownerType = currPackage.resolveType(name, this)
            name = tokens.toString(i++)
        } else if (tokens.equals(i, "?.")) {
            check(tokens.equals(++i, TokenType.NAME))
            ownerType = currPackage.resolveType(name, this)
            ownerType = typeOrNull(ownerType)
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

        if (ownerType == null) ownerType = getSelfType(currPackage)
        val field = Field(
            currPackage, isVar, !isVar, ownerType,
            name, valueType, initialValue, keywords, origin
        )
        if (debug) println("read field $name: $valueType = $initialValue")
        lastField = field
    }

    private fun skipTypeParametersToFindFunctionNameAndScope(): Scope {
        var j = i
        if (tokens.equals(j, "<")) {
            j = tokens.findBlockEnd(j, "<", ">") + 1
        }
        check(tokens.equals(j, TokenType.NAME))
        val name = tokens.toString(j)
        val name1 = currPackage.generateName("f:$name")
        return currPackage.getOrPut(name1, tokens.fileName, ScopeType.METHOD)
    }

    private fun readMethodSelfType(typeParameters: List<Parameter>, functionScope: Scope): Type? {
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
                var type = readType()
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

    private fun readWhereConditions(): List<TypeCondition> {
        return if (tokens.equals(i, "where")) {
            i++ // skip where
            val conditions = ArrayList<TypeCondition>()
            while (true) {

                check(tokens.equals(i, TokenType.NAME))
                check(tokens.equals(i + 1, ":"))

                val name = tokens.toString(i++)
                i++ // skip comma
                val type = readType()

                conditions.add(TypeCondition(name, type))

                if (tokens.equals(i, ",") &&
                    tokens.equals(i + 1, TokenType.NAME) &&
                    tokens.equals(i + 2, ":")
                ) {
                    i++ // skip comma and continue reading conditions
                } else {
                    // done
                    break
                }
            }
            conditions
        } else emptyList()
    }

    private fun readMethod(): Method {
        val origin = origin(i++) // skip 'fun'

        val keywords = packKeywords()

        // parse optional <T, U>
        val clazz = currPackage
        val methodScope = skipTypeParametersToFindFunctionNameAndScope()
        val typeParameters = readTypeParameterDeclarations(methodScope)

        check(tokens.equals(i, TokenType.NAME))
        val selfType = readMethodSelfType(typeParameters, methodScope)
            ?: getSelfType(methodScope)

        check(tokens.equals(i, TokenType.NAME))
        val name = tokens.toString(i++)

        if (debug) println("fun <$typeParameters> ${if (selfType != null) "$selfType." else ""}$name(...")

        // parse parameters (...)
        check(tokens.equals(i, TokenType.OPEN_CALL))

        lateinit var parameters: List<Parameter>
        pushScope(methodScope) {
            parameters = pushCall {
                val selfType = ClassType(clazz, null)
                readParamDeclarations(selfType)
            }
        }

        // optional return type
        var returnType = if (tokens.equals(i, ":")) {
            i++ // skip :
            readType()
        } else null

        val extraConditions = readWhereConditions()

        // body (or just = expression)
        val body = pushScope(methodScope) {
            if (tokens.equals(i, "=")) {
                i++ // skip =
                readExpression()
            } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                if (returnType == null) returnType = UnitType
                pushBlock(ScopeType.METHOD_BODY, methodScope.name) { readMethodBody() }
            } else {
                if (returnType == null) returnType = UnitType
                null
            }
        }

        popGenericParams()

        val method = Method(
            selfType, name, typeParameters, parameters, methodScope,
            returnType, extraConditions, body, keywords, origin
        )
        methodScope.selfAsMethod = method
        currPackage.methods.add(method)
        return method
    }

    private fun readConstructor(): Constructor {
        i++ // skip 'constructor'

        val origin = origin(i)
        val keywords = packKeywords()

        if (debug) println("constructor(...")

        // parse parameters (...)
        check(tokens.equals(i, TokenType.OPEN_CALL))
        lateinit var parameters: List<Parameter>
        val clazz = currPackage
        val innerScope = pushScope("constructor", ScopeType.CONSTRUCTOR_PARAMS) { scope ->
            val selfType = ClassType(clazz, null)
            parameters = pushCall { readParamDeclarations(selfType) }
            scope
        }

        // optional return type
        val superCall = if (tokens.equals(i, ":")) {
            i++
            check(tokens.equals(i, "this") || tokens.equals(i, "super"))
            val origin = origin(i)
            val name = tokens.toString(i++)
            val typeParams = readTypeParams()
            val params = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                pushCall { readParamExpressions() }
            } else emptyList()
            val base = VariableExpression(name, origin, this, clazz)
            CallExpression(base, typeParams, params, origin + 1)
        } else null

        // body (or just = expression)
        val body = pushScope(innerScope) {
            if (tokens.equals(i, "=")) {
                i++ // skip =
                readExpression()
            } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                pushBlock(ScopeType.CONSTRUCTOR, null) { readMethodBody() }
            } else null
        }

        val constructor = Constructor(
            clazz, parameters, innerScope,
            superCall, body, keywords, origin
        )
        currPackage.constructors.add(constructor)
        return constructor
    }

    fun readFileLevel() {
        loop@ while (i < tokens.size) {
            if (debug) println("readFileLevel[$i]: ${tokens.err(i)}")
            when {
                tokens.equals(i, "package") -> {
                    i++ // skip 'package'
                    currPackage = readPath()
                    currPackage.mergeScopeTypes(ScopeType.PACKAGE)
                }
                tokens.equals(i, "import") -> {
                    i++ // skip 'import'
                    val path = readPath()
                    val allChildren = tokens.equals(i, ".*")
                    if (allChildren) i++ // skip .* symbol
                    val name = if (!allChildren && tokens.equals(i, "as") && tokens.equals(i + 1, TokenType.NAME)) {
                        i++
                        tokens.toString(i++)
                    } else path.name

                    imports.add(Import(path, allChildren, name))
                    if (allChildren) {
                        for (child in path.children) {
                            currPackage.imports += Import2(child.name, child, false)
                        }
                    } else {
                        currPackage.imports += Import2(name, path, true)
                    }
                }

                tokens.equals(i, "class") -> readClass()
                tokens.equals(i, "object") -> readObject()
                tokens.equals(i, "fun") -> {
                    if (tokens.equals(i + 1, "interface")) {
                        keywords.add("fun"); i++
                        readInterface()
                    } else readMethod()
                }
                tokens.equals(i, "interface") -> readInterface()
                tokens.equals(i, "constructor") -> readConstructor()
                tokens.equals(i, "typealias") -> readTypeAlias()
                tokens.equals(i, "var") -> readVarValInClass(true)
                tokens.equals(i, "val") -> readVarValInClass(false)
                tokens.equals(i, "init") -> {
                    check(tokens.equals(++i, TokenType.OPEN_BLOCK))
                    pushBlock(currPackage.getOrCreatePrimConstructorScope()) {
                        readMethodBody()
                    }
                }

                tokens.equals(i, "get") -> {
                    check(tokens.equals(++i, TokenType.OPEN_CALL))
                    check(tokens.equals(++i, TokenType.CLOSE_CALL))

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
                            field.getterExpr = pushBlock(ScopeType.FIELD_GETTER, "${field.name}:get") {
                                readMethodBody()
                            }
                        }
                        else -> throw IllegalStateException("Expected = or {} after get() at ${tokens.err(i)}")
                    }
                }
                tokens.equals(i, "set") -> {
                    i++ // skip set
                    val field = lastField!!
                    field.privateSet = keywords.remove("private")
                    if (tokens.equals(i, TokenType.OPEN_CALL)) {
                        check(tokens.equals(++i, TokenType.NAME))
                        field.setterFieldName = tokens.toString(i++)
                        if (debug) println("found set ${field.name}, ${field.setterFieldName}")
                        check(tokens.equals(i++, TokenType.CLOSE_CALL))
                        field.setterExpr = if (tokens.equals(i, "=")) {
                            i++ // skip =
                            readExpression()
                        } else if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                            pushBlock(ScopeType.FIELD_SETTER, "${field.name}:set") {
                                readMethodBody()
                            }
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
        check(tokens.equals(i++, "typealias"))
        check(tokens.equals(i, TokenType.NAME))
        val newName = tokens.toString(i++)
        val pseudoScope = currPackage.getOrPut(newName, tokens.fileName, ScopeType.TYPE_ALIAS)
        pseudoScope.typeParameters = readTypeParameterDeclarations(pseudoScope)

        check(tokens.equals(i++, "="))
        val trueType = readType()
        pseudoScope.typeAlias = trueType
    }

    fun readAnnotation(): Annotation {
        check(tokens.equals(i++, "@"))
        if (tokens.equals(i, TokenType.NAME) &&
            tokens.equals(i + 1, ":") &&
            tokens.equals(i + 2, TokenType.NAME)
        ) {
            // skipping scope
            i += 2
        }
        check(tokens.equals(i, TokenType.NAME))
        val path = readTypePath()
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

        throw IllegalStateException("Unknown keyword ${tokens.toString(i)} at ${tokens.err(i)}")
    }

    fun readParamExpressions(): ArrayList<NamedParameter> {
        val params = ArrayList<NamedParameter>()
        while (i < tokens.size) {
            val name = if (tokens.equals(i, TokenType.NAME) &&
                tokens.equals(i + 1, "=")
            ) tokens.toString(i).apply { i += 2 } else null
            val value = readExpression()
            val param = NamedParameter(name, value)
            params.add(param)
            if (debug) println("read param: $param")
            readComma()
        }
        return params
    }

    fun readParamDeclarations(selfType: Type?): List<Parameter> {
        // todo when this has its own '=', this needs its own scope...,
        //  and that scope could be inherited by the function body...
        val result = ArrayList<Parameter>()
        loop@ while (i < tokens.size) {
            when {
                tokens.equals(i, "@") -> annotations.add(readAnnotation())
                tokens.equals(i, TokenType.NAME) || tokens.equals(i, TokenType.KEYWORD) -> {
                    val origin = origin(i)
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
                    val isVararg = keywords.remove("vararg")
                    check(tokens.equals(i++, ":")) { "Expected colon in var/val at ${tokens.err(i - 1)}" }

                    var type = readType() // <-- handles generics now
                    if (isVararg) type = ClassType(ArrayType.clazz, listOf(type))

                    val initialValue = if (tokens.equals(i, "=")) {
                        i++
                        readExpression()
                    } else null

                    val keywords = packKeywords()
                    result.add(Parameter(isVar, isVal, isVararg, name, type, initialValue, origin))

                    // automatically gets added to currPackage...
                    Field(currPackage, isVar, isVal, selfType, name, type, initialValue, keywords, origin)

                    readComma()
                }
                else -> throw NotImplementedError("Unknown token in params at ${tokens.err(i)}")
            }
        }
        return result
    }

    fun readLambdaParameter(): List<LambdaParameter> {
        val result = ArrayList<LambdaParameter>()
        loop@ while (i < tokens.size) {
            if (tokens.equals(i, TokenType.NAME) &&
                tokens.equals(i + 1, ":")
            // && tokens.equals(i + 2, TokenType.NAME)
            ) {
                val name = tokens.toString(i)
                i += 2
                result.add(LambdaParameter(name, readType()))
            } else if (tokens.equals(i, TokenType.NAME)) {
                result.add(LambdaParameter(null, readType()))
            } else throw IllegalStateException("Expected name: Type or name at ${tokens.err(i)}")
            readComma()
        }
        return result
    }

    fun pushGenericParams() {
        genericParams.add(HashMap(genericParams.last()))
    }

    fun popGenericParams() {
        genericParams.removeLast()
    }

    fun readTypeParameterDeclarations(scope: Scope): List<Parameter> {
        pushGenericParams()
        if (!tokens.equals(i, "<")) return emptyList()
        val params = ArrayList<Parameter>()
        tokens.push(i++, "<", ">") {
            while (i < tokens.size) {
                // todo store & use these?
                if (tokens.equals(i, "in")) i++
                if (tokens.equals(i, "out")) i++

                check(tokens.equals(i, TokenType.NAME)) { "Expected type parameter name" }
                val origin = origin(i)
                val name = tokens.toString(i++)

                // name might be needed for the type, so register it already here
                genericParams.last()[name] = GenericType(scope, name)

                val type = if (tokens.equals(i, ":")) {
                    i++ // skip :
                    readType()
                } else NullableAnyType

                params.add(Parameter(false, true, false, name, type, null, origin))
                readComma()
            }
        }
        check(tokens.equals(i++, ">")) // skip >
        scope.typeParameters += params
        scope.hasTypeParameters = true
        return params
    }

    fun readExpressionCondition(): Expression {
        return pushCall { readExpression() }
    }

    fun readBodyOrExpression(): Expression {
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
                            return readExprInNewScope()
                        }
                    }
                    else -> break@arrowSearch
                }
                j++
            }

            pushBlock(ScopeType.EXPRESSION, null) { scope ->
                readMethodBody()
            }
        } else {
            readExprInNewScope()
        }
    }

    private fun readExprInNewScope(): Expression {
        return pushScope(currPackage.generateName("expr"), ScopeType.EXPRESSION) { scope ->
            readExpression()
        }
    }

    fun origin(i: Int): Int {
        return TokenListIndex.getIndex(tokens, i)
    }

    fun readPrefix(): Expression {

        val label =
            if (tokens.equals(i, TokenType.LABEL)) tokens.toString(i++)
            else null

        return when {
            tokens.equals(i, "@") -> {
                val annotation = readAnnotation()
                AnnotatedExpression(annotation, readPrefix())
            }
            tokens.equals(i, "null") -> SpecialValueExpression(SpecialValue.NULL, currPackage, origin(i++))
            tokens.equals(i, "true") -> SpecialValueExpression(SpecialValue.TRUE, currPackage, origin(i++))
            tokens.equals(i, "false") -> SpecialValueExpression(SpecialValue.FALSE, currPackage, origin(i++))
            tokens.equals(i, "this") -> SpecialValueExpression(SpecialValue.THIS, currPackage, origin(i++))
            tokens.equals(i, "super") -> SpecialValueExpression(SpecialValue.SUPER, currPackage, origin(i++))
            tokens.equals(i - 1, "::") && tokens.equals(i, "class") -> {
                SpecialValueExpression(SpecialValue.CLASS, currPackage, origin(i++))
            }
            tokens.equals(i, TokenType.NUMBER) -> NumberExpression(tokens.toString(i), currPackage, origin(i++))
            tokens.equals(i, TokenType.STRING) -> StringExpression(tokens.toString(i), currPackage, origin(i++))
            tokens.equals(i, "!") -> PrefixExpression(PrefixType.NOT, origin(i++), readExpression())
            tokens.equals(i, "-") -> PrefixExpression(PrefixType.MINUS, origin(i++), readExpression())
            tokens.equals(i, "++") -> PrefixExpression(PrefixType.INCREMENT, origin(i++), readExpression())
            tokens.equals(i, "--") -> PrefixExpression(PrefixType.DECREMENT, origin(i++), readExpression())
            tokens.equals(i, "*") -> PrefixExpression(PrefixType.ARRAY_TO_VARARGS, origin(i++), readExpression())
            tokens.equals(i, "+") -> {
                i++; readPrefix()
            }
            tokens.equals(i, "::") -> {
                val origin = origin(i++)
                check(tokens.equals(i, TokenType.NAME))
                val name = tokens.toString(i++)
                // :: means a function of the current class
                DoubleColonPrefix(currPackage, name, currPackage, origin)
            }

            tokens.equals(i, "if") -> readIfBranch()
            tokens.equals(i, "else") -> throw IllegalStateException("Unexpected else at ${tokens.err(i)}")
            tokens.equals(i, "while") -> readWhileLoop(label)
            tokens.equals(i, "do") -> readDoWhileLoop(label)
            tokens.equals(i, "for") -> readForLoop(label)
            tokens.equals(i, "when") -> {
                i++
                when {
                    tokens.equals(i, TokenType.OPEN_CALL) -> readWhenWithSubject()
                    tokens.equals(i, TokenType.OPEN_BLOCK) -> readWhenWithConditions()
                    else -> throw IllegalStateException("Unexpected token after when at ${tokens.err(i)}")
                }
            }
            tokens.equals(i, "try") -> readTryCatch()
            tokens.equals(i, "return") -> readReturn(label)
            tokens.equals(i, "throw") -> ThrowExpression(origin(i++), readExpression())
            tokens.equals(i, "break") -> BreakExpression(label, currPackage, origin(i++))
            tokens.equals(i, "continue") -> ContinueExpression(label, currPackage, origin(i++))

            tokens.equals(i, "object") &&
                    tokens.equals(i + 1, TokenType.OPEN_BLOCK) -> readInlineClass0()

            tokens.equals(i, "object") &&
                    tokens.equals(i + 1, ":") -> readInlineClass()

            tokens.equals(i, TokenType.NAME) -> {
                val origin = origin(i)
                val namePath = tokens.toString(i++)
                val typeArgs = readTypeParams()
                if (
                    tokens.equals(i, TokenType.OPEN_CALL) &&
                    tokens.isSameLine(i - 1, i)
                ) {
                    // constructor or function call with type args
                    val start = i
                    val end = tokens.findBlockEnd(i, TokenType.OPEN_CALL, TokenType.CLOSE_CALL)
                    if (debug) println(
                        "tokens for params: ${
                            (start..end).map { idx ->
                                "${tokens.getType(idx)}(${tokens.toString(idx)})"
                            }
                        }"
                    )
                    val args = pushCall { readParamExpressions() }
                    val base = VariableExpression(namePath, origin, this, currPackage)
                    CallExpression(base, typeArgs, args, origin + 1)
                } else {
                    VariableExpression(namePath, origin, this, currPackage)
                }
            }
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                // just something in brackets
                pushCall { readExpression() }
            }
            tokens.equals(i, TokenType.OPEN_BLOCK) ->
                pushBlock(ScopeType.LAMBDA, null) { readLambda() }

            else -> {
                tokens.printTokensInBlocks(max(i - 5, 0))
                throw NotImplementedError("Unknown expression part at ${tokens.err(i)}")
            }
        }
    }

    private fun readInlineClass0(): Expression {
        val origin = origin(i)
        check(tokens.equals(i++, "object"))
        check(tokens.equals(i, TokenType.OPEN_BLOCK))

        val name = currPackage.generateName("lambda")
        val clazz = currPackage.getOrPut(name, tokens.fileName, ScopeType.INLINE_CLASS)

        readClassBody(name, emptyList(), ScopeType.INLINE_CLASS)
        return ConstructorExpression(clazz, emptyList(), emptyList(), currPackage, origin)
    }

    private fun readInlineClass(): Expression {
        val origin = origin(i)
        check(tokens.equals(i++, "object"))
        check(tokens.equals(i++, ":"))

        val name = currPackage.generateName("inline")
        val clazz = currPackage.getOrPut(name, tokens.fileName, ScopeType.INLINE_CLASS)

        val bodyIndex = tokens.findToken(i, TokenType.OPEN_BLOCK)
        check(bodyIndex > i)
        push(bodyIndex) {
            while (i < tokens.size) {
                clazz.superCalls.add(readSuperCall())
                readComma()
            }
        }
        i = bodyIndex
        readClassBody(name, emptyList(), ScopeType.INLINE_CLASS)
        return ConstructorExpression(clazz, emptyList(), emptyList(), currPackage, origin)
    }

    private fun readSuperCall(): SuperCall {
        val type = readType()

        val valueParams = if (tokens.equals(i, TokenType.OPEN_CALL)) {
            pushCall { readParamExpressions() }
        } else null

        val delegate = if (tokens.equals(i, "by")) {
            i++ // skip by
            readExpression()
        } else null

        return SuperCall(type, valueParams, delegate)
    }

    private fun readIfBranch(): IfElseBranch {
        i++
        val condition = readExpressionCondition()
        val ifTrue = readBodyOrExpression()
        val ifFalse = if (tokens.equals(i, "else") && !tokens.equals(i + 1, "->")) {
            i++
            readBodyOrExpression()
        } else null
        return IfElseBranch(condition, ifTrue, ifFalse)
    }

    private fun readWhileLoop(label: String?): WhileLoop {
        i++
        val condition = readExpressionCondition()
        val body = readBodyOrExpression()
        return WhileLoop(condition, body, label)
    }

    private fun readDoWhileLoop(label: String?): Expression {
        i++
        val body = readBodyOrExpression()
        check(tokens.equals(i++, "while"))
        val condition = readExpressionCondition()
        return DoWhileLoop(body = body, condition = condition, label)
    }

    private fun readForLoop(label: String?): Expression {
        i++ // skip for
        if (tokens.equals(i + 1, TokenType.OPEN_CALL)) {
            val names = ArrayList<String>()
            lateinit var iterable: Expression
            pushCall {
                check(tokens.equals(i, TokenType.OPEN_CALL))
                pushCall {
                    while (i < tokens.size) {
                        check(tokens.equals(i, TokenType.NAME))
                        names.add(tokens.toString(i++))
                        readComma()
                    }
                }
                // to do type?
                check(tokens.equals(i++, "in"))
                iterable = readExpression()
                check(i == tokens.size)
            }
            val body = readBodyOrExpression()
            return DestructuringForLoop(currPackage, names, iterable, body, label)
        } else {
            lateinit var name: String
            lateinit var iterable: Expression
            var variableType: Type? = null
            pushCall {
                check(tokens.equals(i, TokenType.NAME))
                name = tokens.toString(i++)
                variableType = if (tokens.equals(i, ":")) {
                    i++ // skip :
                    readType()
                } else null
                // to do type?
                check(tokens.equals(i++, "in"))
                iterable = readExpression()
                check(i == tokens.size)
            }
            val body = readBodyOrExpression()
            return ForLoop(name, variableType, iterable, body, label)
        }
    }

    private fun readWhenWithSubject(): Expression {
        val subject = pushCall {
            when {
                tokens.equals(i, "val") -> readDeclaration(false, isLateinit = false)
                tokens.equals(i, "var") -> readDeclaration(true, isLateinit = false)
                else -> readExpression()
            }
        }
        check(tokens.equals(i, TokenType.OPEN_BLOCK))
        val cases = ArrayList<SubjectWhenCase>()
        val childScope = pushBlock(ScopeType.WHEN_CASES, null) { childScope ->
            while (i < tokens.size) {
                if (cases.isNotEmpty()) pushHelperScope()
                val nextArrow = findNextArrow(i)
                check(nextArrow > i)
                val conditions = readSubjectConditions(nextArrow)
                val body = readBodyOrExpression()
                if (false) {
                    println("next case:")
                    println("  condition-scope: ${currPackage.pathStr}")
                    println("  body-scope: ${body.scope}")
                }
                cases.add(SubjectWhenCase(conditions, currPackage, body))
            }
            childScope
        }
        return whenSubjectToIfElseChain(childScope, subject, cases)
    }

    private fun readSubjectConditions(nextArrow: Int): List<SubjectCondition?> {
        val conditions = ArrayList<SubjectCondition?>()
        if (debug) println("reading conditions, ${tokens.toString(i, nextArrow)}")
        push(nextArrow) {
            while (i < tokens.size) {
                if (debug) println("  reading condition $nextArrow,$i,${tokens.err(i)}")
                when {
                    tokens.equals(i, "else") -> {
                        i++; conditions.add(null)
                    }
                    tokens.equals(i, "in") || tokens.equals(i, "!in") -> {
                        val symbol =
                            if (tokens.toString(i++) == "in") SubjectConditionType.CONTAINS
                            else SubjectConditionType.NOT_CONTAINS
                        val value = readExpression()
                        val extra = if (tokens.equals(i, "if")) {
                            i++
                            readExpression()
                        } else null
                        conditions.add(SubjectCondition(value, null, symbol, extra))
                    }
                    tokens.equals(i, "is") || tokens.equals(i, "!is") -> {
                        val symbol =
                            if (tokens.toString(i++) == "is") SubjectConditionType.INSTANCEOF
                            else SubjectConditionType.NOT_INSTANCEOF
                        val type = readType()
                        val extra = if (tokens.equals(i, "if")) {
                            i++
                            readExpression()
                        } else null
                        conditions.add(SubjectCondition(null, type, symbol, extra))
                    }
                    else -> {
                        val value = readExpression()
                        val extra = if (tokens.equals(i, "if")) {
                            i++
                            readExpression()
                        } else null
                        conditions.add(SubjectCondition(value, null, SubjectConditionType.EQUALS, extra))
                    }
                }
                if (debug) println("  read condition '${conditions.last()}'")
                readComma()
            }
        }
        if (conditions.isEmpty()) {
            throw IllegalStateException("Missing conditions at ${tokens.err(i)}")
        }
        return conditions
    }

    private fun pushHelperScope() {
        // push a helper scope for if/else differentiation...
        val type = ScopeType.WHEN_ELSE
        val name = currPackage.generateName(type.name)
        currPackage = currPackage.getOrPut(name, type)
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
                    if (debug) println("skipping over type '$type'")
                    j = i - 1 // continue after the type; -1, because will be incremented immediately after
                    i = originalI
                }
                depth == 0 && tokens.equals(j, "->") -> {
                    if (debug) println("found arrow at ${tokens.err(j)}")
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

    private fun readWhenWithConditions(): Expression {
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
                    if (tokens.equals(i, "else")) null
                    else readExpression()
                }

                val body = readBodyOrExpression()
                cases.add(WhenCase(condition, body))
            }
        }
        return whenBranchToIfElseChain(cases, currPackage, origin)
    }

    private fun readReturn(label: String?): ReturnExpression {
        val origin = origin(i++) // skip return
        if (debug) println("reading return")
        if (i < tokens.size && tokens.isSameLine(i - 1, i) &&
            !tokens.equals(i, TokenType.COMMA)
        ) {
            val value = readExpression()
            if (debug) println("  with value $value")
            return ReturnExpression(value, label, currPackage, origin)
        } else {
            if (debug) println("  without value")
            return ReturnExpression(null, label, currPackage, origin)
        }
    }

    private fun readTryCatch(): TryCatchBlock {
        i++ // skip try
        val tryBody = readBodyOrExpression()
        val catches = ArrayList<Catch>()
        while (tokens.equals(i, "catch")) {
            check(tokens.equals(++i, TokenType.OPEN_CALL))
            val params = pushCall { readParamDeclarations(null) }
            check(params.size == 1)
            val handler = readBodyOrExpression()
            catches.add(Catch(params[0], handler))
        }
        val finally = if (tokens.equals(i, "finally")) {
            i++ // skip finally
            readBodyOrExpression()
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
            if (debug) println("  check ${tokens.err(i)} for type-args-compatibility")
            // todo support annotations here?
            when {
                tokens.equals(i, TokenType.COMMA) -> {} // ok
                tokens.equals(i, TokenType.NAME) -> {} // ok
                tokens.equals(i, "<") -> depth++
                tokens.equals(i, ">") -> depth--
                tokens.equals(i, "?") ||
                        tokens.equals(i, "->") ||
                        tokens.equals(i, ":") || // names are allowed
                        tokens.equals(i, ".") ||
                        tokens.equals(i, "in") ||
                        tokens.equals(i, "out") ||
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
                        tokens.equals(i, "var") ||
                        tokens.equals(i, "else") ||
                        tokens.equals(i, "fun") ||
                        tokens.equals(i, "this") -> return false
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
                val parameters = pushCall { readLambdaParameter() }
                i = endI + 2 // skip ) and ->
                val returnType = readType()
                return LambdaType(parameters, returnType)
            } else {
                val baseType = pushCall { readType() }
                val isNullable = consumeNullable()
                return if (isNullable) typeOrNull(baseType) else baseType
            }
        }

        val path = readTypePath() // e.g. ArrayList
        val subType = if (tokens.equals(i, ".") && tokens.equals(i + 1, "(")) {
            i++ // skip ., and then read lambda subtype
            readType()
            TODO(
                "We somehow need to support types like Map<K,V>.Iterator<J>, where Iterator is an inner class... " +
                        "or we just forbid inner classes"
            )
        } else null

        val typeArgs = readTypeParams()
        val isNullable = consumeNullable()
        val baseType =
            if (path is ClassType) ClassType(path.clazz, typeArgs)
            else if (typeArgs == null) path
            else throw IllegalStateException("Cannot combine $path with $typeArgs and $subType")
        return if (isNullable) typeOrNull(baseType) else baseType
    }

    private fun consumeNullable(): Boolean {
        val isNullable = tokens.equals(i, "?")
        if (isNullable) i++
        return isNullable
    }

    fun readTypeParams(): List<Type>? {
        if (i < tokens.size) {
            if (debug) println("checking for type-args, ${tokens.err(i)}, ${isTypeArgsStartingHere(i)}")
        }
        // having type arguments means they no longer need to be resolved
        // todo any method call without them must resolve which ones and how many there are, e.g. mapOf, listOf, ...
        if (!isTypeArgsStartingHere(i)) {
            return null
        }

        i++ // consume '<'

        val args = ArrayList<Type>()
        while (true) {
            // todo store these (?)
            if (tokens.equals(i, "in")) i++
            if (tokens.equals(i, "out")) i++
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
        val result = tokens.push(i++, TokenType.OPEN_CALL, TokenType.CLOSE_CALL, readImpl)
        i++ // skip )
        return result
    }

    fun <R> pushArray(readImpl: () -> R): R {
        val result = tokens.push(i++, TokenType.OPEN_ARRAY, TokenType.CLOSE_ARRAY, readImpl)
        i++ // skip ]
        return result
    }

    fun <R> pushBlock(scopeType: ScopeType, scopeName: String?, readImpl: (Scope) -> R): R {
        val name = scopeName ?: currPackage.generateName(scopeType.name)
        return pushScope(name, scopeType) { childScope ->
            childScope.keywords.add(scopeType.name)

            val blockEnd = tokens.findBlockEnd(i++, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK)
            scanBlockForNewTypes(i, blockEnd)
            val result = tokens.push(blockEnd) { readImpl(childScope) }
            i++ // skip }
            result
        }
    }

    fun <R> pushBlock(scope: Scope, readImpl: (Scope) -> R): R {
        return pushScope(scope) {
            val blockEnd = tokens.findBlockEnd(i++, TokenType.OPEN_BLOCK, TokenType.CLOSE_BLOCK)
            scanBlockForNewTypes(i, blockEnd)
            val result = tokens.push(blockEnd) { readImpl(scope) }
            i++ // skip }
            result
        }
    }

    /**
     * to make type-resolution immediately available/resolvable
     * */
    fun scanBlockForNewTypes(i0: Int, i1: Int) {
        var depth = 0
        var listen = -1
        var listenType = ""
        var typeDepth = 0
        for (i in i0 until i1) {
            when (tokens.getType(i)) {
                TokenType.OPEN_BLOCK -> {
                    depth++
                    listen = -1
                }
                TokenType.OPEN_CALL, TokenType.OPEN_ARRAY -> depth++
                TokenType.CLOSE_CALL, TokenType.CLOSE_BLOCK, TokenType.CLOSE_ARRAY -> depth--
                else -> {
                    if (depth == 0) {
                        when {
                            tokens.equals(i, "<") -> if (listen >= 0) typeDepth++
                            tokens.equals(i, ">") -> if (listen >= 0) typeDepth--
                            // tokens.equals(i, "var") || tokens.equals(i, "val") ||
                            // tokens.equals(i, "fun") ||
                            tokens.equals(i, "class") && !tokens.equals(i - 1, "::") -> {
                                listen = i
                                listenType = "class"
                            }
                            tokens.equals(i, "object") && !tokens.equals(i + 1, ":") -> {
                                listen = i
                                listenType = "object"
                            }
                            tokens.equals(i, "interface") -> {
                                listen = i
                                listenType = "interface"
                            }
                            typeDepth == 0 && tokens.equals(i, TokenType.NAME) && listen >= 0 &&
                                    fileLevelKeywords.none { keyword -> tokens.equals(i, keyword) } -> {
                                currPackage
                                    .getOrPut(tokens.toString(i), tokens.fileName, null)
                                    .keywords.add(listenType)
                                if (debug) println("found ${tokens.toString(i)} in $currPackage")
                                listen = -1
                                listenType = ""
                            }
                        }
                    }
                }
            }
        }
        check(listen == -1) { "Listening for class/object/interface at ${tokens.err(listen)}" }
    }

    fun <R> push(endTokenIdx: Int, readImpl: () -> R): R {
        val result = tokens.push(endTokenIdx, readImpl)
        i = endTokenIdx + 1 // skip }
        return result
    }

    fun readExpression(minPrecedence: Int = 0): Expression {
        var expr = readPrefix()
        if (debug) println("prefix: $expr")

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
            when (symbol) {
                "in", "!in", "is", "!is", "+", "-" -> {
                    // these must be on the same line
                    if (!tokens.isSameLine(i - 1, i)) {
                        break@loop
                    }
                }
            }

            if (debug) println("symbol $symbol, valid? ${symbol in operators}")

            val op = operators[symbol]
            if (op == null) {
                // postfix
                expr = tryReadPostfix(expr) ?: break@loop
            } else {

                if (op.precedence < minPrecedence) break@loop

                val origin = origin(i)
                i++ // consume operator

                fun readRHS(): Expression =
                    readExpression(if (op.assoc == Assoc.LEFT) op.precedence + 1 else op.precedence)

                expr = when (symbol) {
                    "as" -> {
                        val rhs = readType()
                        BinaryTypeOp(expr, BinaryTypeOpType.CAST_OR_CRASH, rhs, origin)
                    }
                    "as?" -> {
                        val rhs = readType()
                        BinaryTypeOp(expr, BinaryTypeOpType.CAST_OR_NULL, rhs, origin)
                    }
                    "is" -> {
                        val rhs = readType()
                        BinaryTypeOp(expr, BinaryTypeOpType.INSTANCEOF, rhs, origin)
                    }
                    "!is" -> {
                        val rhs = readType()
                        val base = BinaryTypeOp(expr, BinaryTypeOpType.INSTANCEOF, rhs, origin)
                        PrefixExpression(PrefixType.NOT, origin, base)
                    }
                    "." -> {
                        if (tokens.equals(i, TokenType.NAME) &&
                            tokens.equals(i + 1, TokenType.SYMBOL) &&
                            tokens.endsWith(i + 1, '=') &&
                            !tokens.equals(i + 1, "==") &&
                            !tokens.equals(i + 1, "!=") &&
                            !tokens.equals(i + 1, "===") &&
                            !tokens.equals(i + 1, "!==")
                        ) {
                            val name = tokens.toString(i++)
                            if (tokens.equals(i, "=")) {
                                val originI = origin(i++) // skip =
                                val value = readExpression()
                                val nameTitlecase = name[0].uppercaseChar() + name.substring(1)
                                val setterName = "set$nameTitlecase"
                                val param = NamedParameter(null, value)
                                NamedCallExpression(
                                    expr, setterName, null,
                                    listOf(param), expr.scope, originI
                                )
                            } else {
                                // +=, -=, *=, /=, ...
                                val originI = origin(i)
                                val symbol = tokens.toString(i++)
                                val expr1 = VariableExpression(name, originI, this, currPackage)
                                val param1 = NamedParameter(null, expr1)
                                val left = NamedCallExpression(
                                    expr, ".", null,
                                    listOf(param1), expr.scope, originI
                                )
                                val right = readExpression()
                                AssignIfMutableExpr(left, symbol, right)
                            }
                        } else {
                            val rhs = readRHS()
                            binaryOp(currPackage, expr, op.symbol, rhs)
                        }
                    }
                    else -> {
                        val rhs = readRHS()
                        if (isInfix) {
                            val param = NamedParameter(null, rhs)
                            NamedCallExpression(
                                expr, op.symbol, null,
                                listOf(param), expr.scope, origin
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

    fun tryReadPostfix(expr: Expression): Expression? {
        return when {
            i >= tokens.size || !tokens.isSameLine(i - 1, i) -> null
            tokens.equals(i, TokenType.OPEN_CALL) -> {
                val origin = origin(i)
                val params = pushCall { readParamExpressions() }
                if (tokens.equals(i, TokenType.OPEN_BLOCK)) {
                    pushBlock(ScopeType.LAMBDA, null) { params += NamedParameter(null, readLambda()) }
                }
                CallExpression(expr, null, params, origin)
            }
            tokens.equals(i, TokenType.OPEN_ARRAY) -> {
                val origin = origin(i)
                val params = pushArray { readParamExpressions() }
                if (tokens.equals(i, "=")) {
                    i++ // skip =
                    val value = NamedParameter(null, readExpression())
                    NamedCallExpression(
                        expr, "set", null,
                        params + value, expr.scope, origin
                    )
                } else if (tokens.equals(i, TokenType.SYMBOL) && tokens.endsWith(i, '=')) {
                    val symbol = tokens.toString(i++)
                    val value = readExpression()
                    val call = NamedCallExpression(
                        expr, "get/set", null,
                        params, expr.scope, origin
                    )
                    AssignIfMutableExpr(call, symbol, value)
                } else {
                    NamedCallExpression(
                        expr, "get", null,
                        params, expr.scope, origin
                    )
                }
            }
            tokens.equals(i, TokenType.OPEN_BLOCK) -> {
                val origin = origin(i)
                val lambda = pushBlock(ScopeType.LAMBDA, null) { readLambda() }
                val lambdaParam = NamedParameter(null, lambda)
                CallExpression(expr, null, listOf(lambdaParam), origin)
            }
            tokens.equals(i, "++") -> PostfixExpression(expr, PostfixType.INCREMENT, origin(i++))
            tokens.equals(i, "--") -> PostfixExpression(expr, PostfixType.DECREMENT, origin(i++))
            tokens.equals(i, "!!") -> PostfixExpression(expr, PostfixType.ENSURE_NOT_NULL, origin(i++))
            else -> null
        }
    }

    fun readLambda(): Expression {
        val arrow = tokens.findToken(i, "->")
        val variables = if (arrow >= 0) {
            val variables = ArrayList<LambdaVariable>()
            tokens.push(arrow) {
                while (i < tokens.size) {
                    if (tokens.equals(i, TokenType.OPEN_CALL)) {
                        val names = ArrayList<String>()
                        pushCall {
                            while (i < tokens.size) {
                                if (tokens.equals(i, TokenType.NAME)) {
                                    val origin = origin(i)
                                    val name = tokens.toString(i++)
                                    names.add(name)
                                    // todo we neither know type nor initial value :/, both come from the called function/set variable
                                    Field( // this is more of a parameter...
                                        currPackage, false, true, null, name,
                                        null, null, emptyList(), origin
                                    )
                                } else throw IllegalStateException("Expected name")
                                readComma()
                            }
                        }
                        variables.add(LambdaDestructuring(names))
                    } else if (tokens.equals(i, TokenType.NAME)) {
                        val origin = origin(i)
                        val name = tokens.toString(i++)
                        val type = if (tokens.equals(i, ":")) {
                            i++
                            readType()
                        } else null
                        variables.add(LambdaVariable(type, name))
                        // todo we neither know type nor initial value :/, both come from the called function/set variable
                        Field( // this is more of a parameter...
                            currPackage, false, true, null, name,
                            null, null, emptyList(), origin
                        )
                    } else throw NotImplementedError()
                    readComma()
                }
            }
            i++ // skip ->
            variables
        } else null
        val body = readMethodBody()
        check(currPackage.scopeType == ScopeType.LAMBDA)
        return LambdaExpression(variables, currPackage, body)
    }

    fun readComma() {
        if (tokens.equals(i, TokenType.COMMA)) i++
        else if (i < tokens.size) throw IllegalStateException("Expected comma at ${tokens.err(i)}")
    }

    fun readMethodBody(): ExpressionList {
        val origin = origin(i)
        val result = ArrayList<Expression>()
        if (debug) println("reading function body[$i], ${tokens.err(i)}")
        if (debug) tokens.printTokensInBlocks(i)
        while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.CLOSE_BLOCK) ->
                    throw IllegalStateException("} in the middle at ${tokens.err(i)}")
                tokens.equals(i, ";") -> i++ // skip
                tokens.equals(i, "@") -> annotations.add(readAnnotation())
                tokens.equals(i, "val") -> result.add(readDeclaration(false))
                tokens.equals(i, "var") -> result.add(readDeclaration(true))
                tokens.equals(i, "fun") -> {
                    // just read the method, it gets added to the scope
                    readMethod()
                }
                tokens.equals(i, "lateinit") -> {
                    check(tokens.equals(++i, "var"))
                    result.add(readDeclaration(true, isLateinit = true))
                }
                else -> {
                    result.add(readExpression())
                    if (debug) println("block += ${result.last()}")
                }
            }
        }
        val code = ExpressionList(result, currPackage, origin)
        currPackage.code.add(code)
        return code
    }

    private fun readDestructuring(isVar: Boolean, isLateinit: Boolean): DestructuringAssignment {
        val names = ArrayList<String>()
        pushCall {
            while (i < tokens.size) {
                check(tokens.equals(i, TokenType.NAME))
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
        return DestructuringAssignment(names, value, isVar, isLateinit)
    }

    fun readDeclaration(isVar: Boolean, isLateinit: Boolean = false): Expression {
        i++ // skip var/val

        if (tokens.equals(i, TokenType.OPEN_CALL)) {
            return readDestructuring(isVar, isLateinit)
        }

        check(tokens.equals(i, TokenType.NAME))
        val origin = origin(i)
        val name = tokens.toString(i++) // todo name could be path...

        if (tokens.equals(i, ".")) {
            i++
            TODO("read val Vector3d.v get() = x+y")
        }

        if (debug) println("reading var/val $name")
        val type = if (tokens.equals(i, ":")) {
            if (debug) println("skipping : for type")
            i++ // skip :
            readType().apply {
                if (debug) println("type: $this")
            }
        } else {
            if (debug) println("no type present")
            null
        }

        val value = if (tokens.equals(i, "=")) {
            i++ // skip =
            readExpression()
        } else null

        // define variable in the scope
        val field = Field(
            currPackage, isVar, !isVar, getSelfType(currPackage),
            name, type, value, emptyList(), origin
        )

        return DeclarationExpression(currPackage, name, value, field)
    }
}