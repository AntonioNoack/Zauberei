package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile
import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.ExprTypeOp
import me.anno.zauberei.astbuilder.expression.ExprTypeOpType
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.VariableExpression
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.impl.*
import me.anno.zauberei.types.impl.AndType.Companion.andTypes
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * Resolve types step by step, might fail, but should be stable at least.
 * */
object TypeResolution {

    val langScope by lazy { Compile.root.getOrPut("zauberKt", null) }

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::resolveTypesAndNamesImpl)
    }

    private fun forEachScope(scope: Scope, callback: (Scope) -> Unit) {
        callback(scope)
        for (child in scope.children) {
            forEachScope(child, callback)
        }
    }

    private fun isInsideLambda(scope: Scope): Boolean {
        var scope = scope
        while (true) {
            if (scope.scopeType == ScopeType.LAMBDA) return true
            scope = scope.parent ?: return false
        }
    }

    fun resolveTypesAndNamesImpl(scope: Scope) {
        if (isInsideLambda(scope)) {
            // todo parameters usually depend on the context
            return
        }
        val scopeSelfType = getSelfType(scope)
        for (method in scope.methods) {
            getMethodReturnType(scopeSelfType, method)
        }
        for (field in scope.fields) {
            if (field.valueType == null && field.initialValue != null) {
                println("Resolving field $field in scope ${scope.pathStr}")
                println("fieldSelfType: ${field.selfType}")
                println("scopeSelfType: $scopeSelfType")
                //try {
                val context = ResolutionContext(
                    field.declaredScope, field.selfType ?: scopeSelfType,
                    false, null
                )
                field.valueType = resolveType(context, field.initialValue)
                /*} catch (e: Throwable) {
                    e.printStackTrace()
                    // continue anyway for now
                }*/
            }
        }
        if (false) println("${scope.fileName}: ${scope.pathStr}, ${scope.fields.size}f, ${scope.methods.size}m, ${scope.code.size}c")
    }

    fun getMethodReturnType(scopeSelfType: Type?, method: Method): Type? {
        if (method.returnType == null) {
            if (false) println("Resolving ${method.innerScope}.type by ${method.body}")
            val context = ResolutionContext(
                method.innerScope,
                method.selfType ?: scopeSelfType,
                false, null
            )
            method.returnType = resolveType(context, method.body!!)
        }
        return method.returnType
    }

    fun getSelfType(scope: Scope): Type? {
        var scope = scope
        while (true) {
            when (scope.scopeType) {
                ScopeType.NORMAL_CLASS, ScopeType.ENUM_CLASS,
                ScopeType.ENUM_ENTRY_CLASS, ScopeType.INTERFACE,
                ScopeType.OBJECT -> {
                    val typeParams = scope.typeParameters.map { GenericType(scope, it.name) }
                    return ClassType(scope, typeParams)
                }
                else -> scope = scope.parent ?: return null
            }
        }
    }

    fun resolveFieldType(field: Field, scope: Scope): Type {
        val base = field.selfType ?: getSelfType(field.declaredScope)
        return resolveFieldType(base, field, scope)
    }

    fun resolveFieldType(base: Type?, field: Field, scope: Scope): Type {
        var fieldType = field.valueType ?: run {
            val context = ResolutionContext(
                field.declaredScope,
                base, false, null
            )
            resolveType(context, field.initialValue!!)
        }
        field.valueType = fieldType

        // todo valueType is just the general type, there might be much more specific information...
        // todo if the variable is re-assigned, these conditions no longer hold
        println("GeneralFieldType: $fieldType in scope ${scope.pathStr}")

        var scopeI = scope
        while (scopeI.fileName == scope.fileName) {
            val condition = scopeI.branchCondition
            if (condition != null) {

                val prefix = if (scopeI.branchConditionTrue) "" else "!"
                println("  condition: $prefix$condition")

                // decide based on conditionType...
                //  might be inside complex combinations of and, or and not with other conditions...
                var conditionType = when (condition) {
                    is ExprTypeOp -> {
                        println("  -> ExprTypeOf(${condition.left})")
                        var nameExpr = condition.left as? VariableExpression
                        val type = condition.right
                        var matchesName = false
                        while (!matchesName && nameExpr != null) { // todo check if field is actually the same
                            matchesName = nameExpr.name == field.name
                            nameExpr = nameExpr.field?.initialValue as? VariableExpression
                            println("  -> ExprTypeOf/i($nameExpr)")
                        }
                        if (matchesName) {
                            when (condition.op) {
                                ExprTypeOpType.INSTANCEOF -> type
                                ExprTypeOpType.NOT_INSTANCEOF -> type.not()
                                else -> null
                            }
                        } else null
                    }
                    else -> null
                }

                if (conditionType != null) {
                    if (!scopeI.branchConditionTrue) {
                        conditionType = conditionType.not()
                    }
                    fieldType = andTypes(fieldType, conditionType)
                    println("  -> $fieldType (via $conditionType)")
                } else println("  -> condition not yet supported")
            }
            scopeI = scopeI.parent ?: break
        }

        println("SpecializedFieldType: $fieldType")

        return fieldType
    }

    /**
     * resolve the type for a given expression;
     * todo expr can be a lambda,
     *  and then the type not only depends on expr, but what it's used for, too,
     *  e.g. List<Int>.map { it * 2 } -> List<Int>.map(Function1<S,T>),
     *  S <= Int, because there is a only a function List<V>.map(Function1<V,R>).
     * */
    fun resolveType(context: ResolutionContext, expr: Expression): Type {
        // if already resolved, just use that type
        val alreadyResolved = expr.resolvedType
        if (alreadyResolved != null) {
            return alreadyResolved
        } else {
            println("Resolving type of (${expr.javaClass.simpleName}) $expr (targetType=${context.targetType})")
            val type = expr.resolveType(context)
            println("Resolved type of $expr to $type")
            expr.resolvedType = type
            return type
        }
    }

    fun resolveValueParams(
        context: ResolutionContext,
        base: List<NamedParameter>
    ): List<ValueParameter> {
        return base.map { param ->
            if (param.value.hasLambdaOrUnknownGenericsType()) {
                IncompleteValueParameter(param, context)
            } else {
                val type = resolveType(
                    /* no lambda contained -> doesn't matter */
                    context.withTargetType(null),
                    param.value,
                )
                ValueParameterImpl(param.name, type)
            }
        }
    }

    fun resolveThisType(scope: Scope): Scope {
        var scope = scope
        while (true) {
            println("Checking ${scope.pathStr}/${scope.scopeType} for 'this'")
            when (scope.scopeType) {
                ScopeType.NORMAL_CLASS, ScopeType.ENUM_CLASS,
                ScopeType.INTERFACE, ScopeType.OBJECT,
                ScopeType.INLINE_CLASS -> return scope
                ScopeType.METHOD -> {
                    val func = scope.selfAsMethod!!
                    val self = func.selfType
                    if (self != null) {
                        val selfScope = typeToScope(self)!!
                        println("Method-SelfType[${scope.pathStr}]: $self -> $selfScope")
                        return resolveThisType(selfScope)
                    }
                }
                else -> {}
            }
            scope = scope.parent!!
        }
    }

    fun removeNullFromType(type: Type): Type {
        return if (type is UnionType && NullType in type.types) {
            if (type.types.size == 2) type.types.first { it != NullType }
            else UnionType(type.types - NullType)
        } else type
    }

    fun resolveCallType(
        context: ResolutionContext,
        expr: Expression,
        name: String,
        constructor: ResolvedCallable?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): Type {
        println("typeParams: $typeParameters")
        val targetType = context.targetType
        val selfType = context.selfType
        val method = constructor
            ?: findMethod(context.selfScope, true, name, targetType, selfType, typeParameters, valueParameters)
            ?: findMethod(context.codeScope, true, name, targetType, selfType, typeParameters, valueParameters)
            ?: findMethod(langScope, false, name, targetType, selfType, typeParameters, valueParameters)
        val field = findField(context.codeScope, selfType, name)
        val candidates =
            listOfNotNull(method?.getTypeFromCall(), field?.resolveCall()?.getTypeFromCall())
        if (candidates.isEmpty()) {
            val selfScope = context.selfScope
            val codeScope = context.codeScope
            println("self-scope methods[${selfScope?.pathStr}.'$name']: ${selfScope?.methods?.filter { it.name == name }}")
            println("code-scope methods[${codeScope.pathStr}.'$name']: ${codeScope.methods.filter { it.name == name }}")
            println("lang-scope methods[${langScope.pathStr}.'$name']: ${langScope.methods.filter { it.name == name }}")
            throw IllegalStateException(
                "Could not resolve base ${selfScope?.pathStr}.'$name'<$typeParameters>($valueParameters) " +
                        "in ${resolveOrigin(expr.origin)}, scopes: ${codeScope.pathStr}"
            )
        }
        if (candidates.size > 1) throw IllegalStateException("Cannot have both a method and a type with the same name '$name': $candidates")
        return candidates.first()
    }

    fun findFieldType(
        base: Type, name: String, generics: List<Type>,
        scope: Scope, origin: Int
    ): Type? {
        // todo field may be generic, inject the generics as needed...
        // todo check extension fields
        return when (base) {
            NullType, is NotType -> null
            is ClassType -> {
                val fields = base.clazz.fields
                val field = fields.firstOrNull { it.name == name }
                if (field != null) return resolveFieldType(base, field, scope)

                if (base.clazz.scopeType == ScopeType.ENUM_CLASS) {
                    val enumValues = base.clazz.enumValues
                    if (enumValues.any { it.name == name }) {
                        return base.clazz.typeWithoutArgs
                    }
                    TODO("find child class")
                }

                // check super classes and interfaces,
                //  but we need their generics there...
                // -> interfaces can define the field, but it always needs to be in a class, too, so just check super class
                val superCall = base.clazz.superCalls.firstOrNull { it.valueParams != null }
                if (superCall != null) {
                    val superClass = superCall.type as ClassType
                    val superGenerics = superClass.typeParameters ?: emptyList()
                    val genericNames = base.clazz.typeParameters
                    return findFieldType(superClass, name, superGenerics.map { type ->
                        resolveGenerics(type, genericNames, generics)
                    }, scope, origin)
                }// else might be Any, but Any has no fields anyway

                println("No field matched: ${base.clazz.pathStr}.$name: ${fields.map { it.name }}")
                null
            }
            is UnionType -> {
                val baseTypes =
                    base.types.mapNotNull { subType -> findFieldType(subType, name, generics, scope, origin) }
                baseTypes.reduceOrNull { a, b -> unionTypes(a, b) } // union or and?
            }
            is AndType -> {
                val baseTypes =
                    base.types.mapNotNull { subType -> findFieldType(subType, name, generics, scope, origin) }
                baseTypes.reduceOrNull { a, b -> unionTypes(a, b) }
            }
            else -> throw NotImplementedError("findFieldType($base, $name) @ ${resolveOrigin(origin)}")
        }
    }

    fun findField(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): Field? = findField(typeToScope(selfScope), false, name) ?: findField(scope, true, name)

    fun findType(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): ClassType? = findType(typeToScope(selfScope), false, name) ?: findType(scope, true, name)

    fun Field.resolveCall(): ResolvedField {
        return ResolvedField(this)
    }

    fun typeToScope(type: Type?): Scope? {
        return when (type) {
            null -> null
            is ClassType -> type.clazz
            // is NullableType -> typeToScope(type.base)
            else -> throw NotImplementedError("typeToScope($type)")
        }
    }

    fun findField(scope: Scope?, recursive: Boolean, name: String): Field? {
        var scope = scope
        while (scope != null) {

            if (scope.scopeType == ScopeType.OBJECT && scope.name == name) {
                return scope.objectField!!
            }

            val child = scope.children.firstOrNull { it.name == name }
            if (child != null && child.scopeType == ScopeType.OBJECT) {
                return child.objectField!!
            }

            val match = scope.fields.firstOrNull { it.name == name }
            if (match != null) return match

            scope = if (recursive && scope.parent?.fileName == scope.fileName) {
                scope.parent
            } else null
        }
        return null
    }

    fun findType(scope: Scope?, recursive: Boolean, name: String): ClassType? {
        var scope = scope
        while (scope != null) {
            val match = scope.children.firstOrNull { it.name == name }
            if (match != null) return ClassType(match, null)
            scope = if (recursive && scope.parent?.fileName == scope.fileName) {
                scope.parent
            } else null
        }
        return null
    }

    /**
     * finds a method, returns the method and any inserted type parameters
     * */
    fun findMethod(
        scope: Scope?, recursive: Boolean, name: String,
        expectedReturnType: Type?, // sometimes, we know what to expect from the return type
        expectedSelfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        var scope = scope ?: return null
        while (true) {
            val scopeSelfType = getSelfType(scope)
            for (method in scope.methods) {
                if (method.name != name) continue
                if (method.typeParameters.isNotEmpty()) {
                    println("Given $method on $expectedSelfType, with target $expectedReturnType, can we deduct any generics from that?")
                }
                val methodReturnType = if (expectedReturnType != null) {
                    getMethodReturnType(scopeSelfType, method)
                } else method.returnType // no resolution invoked
                val generics = findGenericsForMatch(
                    expectedSelfType, method.selfType,
                    expectedReturnType, methodReturnType,
                    method.typeParameters, typeParameters,
                    method.valueParameters, valueParameters
                ) ?: continue
                return ResolvedMethod(method, generics)
            }

            scope = nextCheckedScope(scope, recursive) ?: return null
        }
    }

    fun findConstructor(
        scope: Scope?, recursive: Boolean, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        var scope = scope ?: return null
        while (true) {
            println("Searching constructors '$name' in ${scope.pathStr}, type: ${scope.scopeType}")
            if (scope.name == name) {
                val found = findConstructorImpl(scope, typeParameters, valueParameters)
                if (found != null) return found
            } else if (scope.scopeType == ScopeType.PACKAGE) {
                println("  ${scope.pathStr} is a package, looking inside")
                for (child in scope.children) {
                    if (child.name == name) {
                        val constructor = findConstructor(
                            child, false, name,
                            typeParameters, valueParameters
                        )
                        println("  constructor candidate for $name: $constructor")
                        if (constructor != null) {
                            return constructor
                        }
                    }
                }
            }

            val bestImport = scope.imports.firstOrNull { it.direct && it.name == name }
                ?: scope.imports.firstOrNull { !it.direct && it.name == name }
            if (bestImport != null) {
                return findConstructor(
                    bestImport.target, false, bestImport.target.name,
                    typeParameters, valueParameters
                )
            }

            scope = nextCheckedScope(scope, recursive) ?: return null
        }
    }

    private fun nextCheckedScope(scope: Scope, recursive: Boolean): Scope? {
        return if (recursive &&
            scope.scopeType != ScopeType.PACKAGE &&
            scope.scopeType != null
        ) {
            scope.parent
        } else null
    }

    fun applyTypeAlias(
        typeParameters: List<Type>?,
        leftTypeParameters: List<Parameter>,
        rightType: Type
    ): ClassType {
        when (rightType) {
            is ClassType -> {
                if (leftTypeParameters.isEmpty()) {
                    check(typeParameters.isNullOrEmpty())
                    // no extra types get applied
                    return rightType
                }

                TODO("$typeParameters x $leftTypeParameters -> ${rightType.clazz.pathStr}<${rightType.typeParameters}>")
            }
            else -> throw NotImplementedError("applyTypeAlias to target $rightType")
        }
    }

    fun findConstructorImpl(
        scope: Scope?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        val scope = scope ?: return null
        val alias = scope.typeAlias
        if (alias != null) {
            val newType = applyTypeAlias(typeParameters, scope.typeParameters, alias)
            println("  mapped ${scope.pathStr}<$typeParameters> via $alias to ${newType.clazz.pathStr}<${newType.typeParameters}>")
            return findConstructorImpl(newType.clazz, newType.typeParameters, valueParameters)
        }

        for (constructor in scope.constructors) {
            println("  candidate constructor: $constructor")
            val generics = findGenericsForMatch(
                null, null,
                null, null,
                constructor.clazz.typeParameters, typeParameters,
                constructor.valueParameters, valueParameters
            ) ?: continue
            return ResolvedConstructor(constructor, generics)
        }
        return null
    }

    fun findGenericsForMatch(
        expectedSelfType: Type?,
        actualSelfType: Type?,

        expectedReturnType: Type?, /* null if nothing is expected */
        actualReturnType: Type?, // can help deducting types

        expectedTypeParameters: List<Parameter>,
        actualTypeParameters: List<Type>?,

        expectedValueParameters: List<Parameter>,
        actualValueParameters: List<ValueParameter>
    ): List<Type>? { // found generic values for a match

        // todo objects don't need actualSelfType, if properly in scope or imported...
        if ((expectedSelfType != null) != (actualSelfType != null)) {
            return null
        }

        println("checking types: $expectedTypeParameters vs $actualTypeParameters")
        println("   and  values: $expectedValueParameters vs $actualValueParameters")
        println("   and  selves: $expectedSelfType vs $actualSelfType")
        println("   and returns: $expectedReturnType vs $actualReturnType")

        // first match everything by name
        // todo resolve default values... -> could be expanded earlier :)
        // todo resolve varargs...

        val isVararg = expectedValueParameters.lastOrNull()?.isVararg == true
        if (isVararg) {
            if (expectedValueParameters.size > actualValueParameters.size) {
                println("param-size too low")
                return null
            }
        } else {
            if (expectedValueParameters.size != actualValueParameters.size) {
                println("param-size mismatch: expected ${expectedValueParameters.size}, but got ${actualValueParameters.size}")
                return null
            }
        }

        if (actualTypeParameters != null && actualTypeParameters.size != expectedTypeParameters.size) {
            println("type-param-size mismatch: expected ${expectedTypeParameters.size}, but got ${actualTypeParameters.size}")
            return null
        }

        val sortedValueParameters = resolveNamedParameters(expectedValueParameters, actualValueParameters)
            ?: run {
                println("param-name mismatch")
                return null
            }

        val resolvedTypes = actualTypeParameters
            ?: FillInParameterList(expectedTypeParameters.size)

        val findGenericTypes = actualTypeParameters == null

        // println("Checking method-match, self-types: $expectedSelfType vs $actualSelfType")
        val matchesSelfType = expectedSelfType == null || isSubTypeOf(
            expectedSelfType, actualSelfType!!,
            expectedTypeParameters,
            resolvedTypes,
            if (findGenericTypes) InsertMode.STRONG else InsertMode.READ_ONLY
        )

        if (!matchesSelfType) {
            // println("selfType-mismatch: $actualSelfType !is $expectedSelfType")
            return null
        }

        // todo this should only be executed sometimes...
        //  missing generic parameters can be temporarily inserted...
        // println("matchesReturnType($expectedReturnType vs $actualReturnType)")
        val matchesReturnType = expectedReturnType == null || actualReturnType == null ||
                isSubTypeOf(
                    expectedReturnType,
                    actualReturnType,
                    expectedTypeParameters,
                    resolvedTypes,
                    if (findGenericTypes) InsertMode.WEAK else InsertMode.READ_ONLY,
                )

        if (!matchesReturnType) {
            // println("returnType-mismatch: $actualReturnType !is $expectedReturnType")
            return null
        }

        for (i in expectedValueParameters.indices) {
            val mvParam = expectedValueParameters[i]
            val vParam = if (mvParam.isVararg) {

                val expectedParamArrayType = mvParam.type
                check(expectedParamArrayType is ClassType)
                check(expectedParamArrayType.clazz == ArrayType.clazz)
                check(expectedParamArrayType.typeParameters?.size == 1)
                // todo we might need to replace generics here...
                val expectedParamEntryType = expectedParamArrayType.typeParameters[0]

                // if star, use it as-is
                val commonType = sortedValueParameters.subList(i, sortedValueParameters.size)
                    .map { it.getType(expectedParamEntryType) }
                    .reduce { a, b -> unionTypes(a, b) }
                val joinedType = ClassType(ArrayType.clazz, listOf(commonType))
                ValueParameterImpl(null, joinedType)
            } else {
                sortedValueParameters[i]
            }
            if (!isSubTypeOf(
                    mvParam, vParam,
                    expectedTypeParameters,
                    resolvedTypes,
                    if (findGenericTypes) InsertMode.STRONG else InsertMode.READ_ONLY
                )
            ) {
                val type = vParam.getType(mvParam.type)
                println("type mismatch: $type is not always a ${mvParam.type}")
                return null
            }
        }

        // todo check that the types are compatible; if they are generics, reduce their type appropriately
        // todo handle all remaining parameters by index, last one may be varargs
        return resolvedTypes as List<Type>
    }

    /**
     * Change the order of value parameters if needed.
     * execution order must remain unchanged!
     * */
    private fun resolveNamedParameters(
        methodValueParameters: List<Parameter>,
        valueParameters: List<ValueParameter>
    ): List<ValueParameter>? {
        return if (valueParameters.any { it.name != null }) {
            val list = arrayOfNulls<ValueParameter>(valueParameters.size)
            for (valueParam in valueParameters) {
                val name = valueParam.name ?: continue
                val index = methodValueParameters.indexOfFirst { it.name == name }
                if (index < 0) return null
                check(list[index] == null)
                list[index] = valueParam
            }
            var index = 0
            for (valueParam in valueParameters) {
                if (valueParam.name != null) continue
                while (list[index] != null) index++
                list[index] = valueParam
            }
            list.toList() as List<ValueParameter>
        } else valueParameters
    }
}