package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile
import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.astbuilder.flow.ForLoop
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.WhileLoop
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.Types.UnitType
import me.anno.zauberei.types.impl.*
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

/**
 * Resolve types step by step.
 * -> good idea, but Kotlin is more complex :(
 * todo -> simple sample, where it fails:
 * fun execute(args: List<String>); execute(emptyList())
 * -> emptyList() - type depends on what it's used for
 * -> todo we need a LazyType -> matchesType() depends on what it's used for :)
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
        for (method in scope.methods) {
            resolveMethodReturnType(method)
        }
        for (field in scope.fields) {
            if (field.valueType == null && field.initialValue != null) {
                println("Resolving field $field in scope ${scope.pathStr}")
                //try {
                val context = ResolutionContext(
                    field.declaredScope, field.selfType, null,
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

    fun resolveMethodReturnType(method: Method): Type {
        if (method.returnType == null) {
            if (false) println("Resolving ${method.innerScope}.type by ${method.body}")
            val context = ResolutionContext(
                method.innerScope,
                method.selfType, typeToScope(method.selfType),
                false, null
            )
            method.returnType = resolveType(context, method.body!!)
        }
        return method.returnType!!
    }

    fun resolveFieldType(field: Field): Type {
        if (field.valueType == null) {
            val context = ResolutionContext(
                field.declaredScope,
                field.selfType, typeToScope(field.selfType),
                false, null
            )
            field.valueType = resolveType(context, field.initialValue!!)
        }
        return field.valueType!!
    }


    /**
     * resolve the type for a given expression;
     * todo expr can be a lambda,
     *  and then the type not only depends on expr, but what it's used for, too,
     *  e.g. List<Int>.map { it * 2 } -> List<Int>.map(Function1<S,T>),
     *  S <= Int, because there is a only a function List<V>.map(Function1<V,R>).
     * */
    fun resolveType(
        context: ResolutionContext,
        expr: Expression,
    ): Type {
        // if already resolved, just use that type
        val alreadyResolved = expr.resolvedType
        if (alreadyResolved != null) {
            return alreadyResolved
        } else {
            println("Resolving type of (${expr.javaClass.simpleName}) $expr")
            val type = resolveTypeImpl(context, expr)
            println("Resolved type of $expr to $type")
            expr.resolvedType = type
            return type
        }
    }

    fun exprContainsLambdaWRTReturnType(expr: Expression?): Boolean {
        // todo what about listOf("1,2,3").map{it.split(',').map{it.toInt()}}?
        //  can we somehow hide lambdas? I don't think so...
        return when (expr) {
            null -> false
            is LambdaExpression -> true
            is NumberExpression -> false
            is IfElseBranch -> {
                exprContainsLambdaWRTReturnType(expr.condition) ||
                        exprContainsLambdaWRTReturnType(expr.ifBranch) ||
                        exprContainsLambdaWRTReturnType(expr.elseBranch)
            }
            is WhileLoop -> {
                // do we need to check the body? not really, because it has no effect on the return type
                exprContainsLambdaWRTReturnType(expr.condition)
            }
            is ForLoop -> {
                exprContainsLambdaWRTReturnType(expr.iterable)
            }
            else -> TODO("Does $expr contain a lambda?")
        }
    }

    fun resolveValueParams(
        context: ResolutionContext,
        base: List<NamedParameter>
    ): List<ValueParameter> {
        return base.map { param ->
            if (exprContainsLambdaWRTReturnType(param.value)) {
                ValueParameterWithLambda(param, context)
            } else {
                val type = resolveType(
                    /* no lambda contained -> doesn't matter */
                    context.withTargetLambdaType(null),
                    param.value,
                )
                ValueParameterImpl(param.name, type)
            }
        }
    }

    /**
     * resolve the type for a given expression;
     * an expression can be
     * */
    fun resolveTypeImpl(
        context: ResolutionContext,
        expr: Expression,
    ): Type {
        when (expr) {
            is CallExpression -> {
                val typeParameters = expr.typeParameters
                val valueParameters = resolveValueParams(context, expr.valueParameters)
                println("Resolving call: ${expr.base}<${typeParameters ?: "?"}>($valueParameters)")
                // todo base can be a constructor, field or a method
                // todo find the best matching candidate...
                when (val base = expr.base) {
                    is NamedCallExpression if base.name == "." -> {
                        TODO("Find method/field ${expr.base}($valueParameters)")
                    }
                    is VariableExpression -> {
                        val name = base.name
                        // findConstructor(selfScope, false, name, typeParameters, valueParameters)
                        val constructor =
                            findConstructor(base.nameAsImport, false, name, typeParameters, valueParameters)
                                ?: findConstructor(context.codeScope, true, name, typeParameters, valueParameters)
                                ?: findConstructor(langScope, false, name, typeParameters, valueParameters)
                        return resolveCallType(
                            context, expr, name, constructor,
                            typeParameters, valueParameters
                        )
                    }
                    else -> throw IllegalStateException(
                        "Resolve field/method for ${base.javaClass} ($base) " +
                                "in ${resolveOrigin(expr.origin)}"
                    )
                }
            }
            is VariableExpression -> {
                val field = findField(context.codeScope, context.selfScope?.typeWithoutArgs, expr.name)
                    ?: findField(langScope, context.selfScope?.typeWithoutArgs, expr.name)
                if (field != null) return resolveFieldType(field)
                val type = findType(context.codeScope, context.selfType, expr.name)
                if (type != null) return type
                throw IllegalStateException(
                    "Missing field '${expr.name}' in ${context.codeScope},${context.selfType}, " +
                            resolveOrigin(expr.origin)
                )
            }
            is WhileLoop -> {
                if (!context.allowTypeless)
                    throw IllegalStateException("Expected type, but found while-loop")
                return UnitType
            }
            is IfElseBranch -> {
                if (expr.elseBranch == null && !context.allowTypeless)
                    throw IllegalStateException("Expected type, but found if without else")
                if (expr.elseBranch == null) return UnitType
                // targetLambdaType stays the same
                val ifType = resolveType(context, expr.ifBranch)
                val elseType = resolveType(context, expr.elseBranch)
                return unionTypes(ifType, elseType)
            }
            is SpecialValueExpression -> {
                return when (expr.value) {
                    SpecialValue.NULL -> NullType
                    SpecialValue.TRUE, SpecialValue.FALSE -> BooleanType
                    SpecialValue.THIS -> {
                        // todo 'this' might have a label, and then means the parent with that name
                        resolveThisType(typeToScope(context.selfType) ?: context.codeScope).typeWithoutArgs
                    }
                    else -> TODO("Resolve type for ConstantExpression in ${context.codeScope},${expr.value}")
                }
            }
            is NamedCallExpression -> {
                val baseType = resolveType(
                    /* targetLambdaType seems not deductible */
                    context.withTargetLambdaType(null),
                    expr.base,
                )
                if (expr.name == ".") {
                    check(expr.valueParameters.size == 1)
                    val parameter0 = expr.valueParameters[0]
                    check(parameter0.name == null)
                    when (val parameter = parameter0.value) {
                        is VariableExpression -> {
                            val fieldName = parameter.name
                            return findFieldType(baseType, fieldName)
                                ?: throw IllegalStateException("Missing $baseType.$fieldName")
                        }
                        is CallExpression -> {
                            val baseName = parameter.base as VariableExpression
                            val constructor = null
                            // todo for lambdas, baseType must be known for their type to be resolved
                            val valueParameters = resolveValueParams(context, parameter.valueParameters)
                            return resolveCallType(
                                context.withSelfType(baseType),
                                expr, baseName.name, constructor,
                                parameter.typeParameters, valueParameters
                            )
                        }
                        else -> TODO("dot-operator with $parameter")
                    }
                } else {

                    val calleeType = resolveType(
                        /* target lambda type seems not deductible */
                        context.withTargetLambdaType(null),
                        expr.base,
                    )
                    // todo type-args may be needed for type resolution
                    val valueParameters = resolveValueParams(context, expr.valueParameters)

                    val constructor = null
                    return resolveCallType(
                        context.withSelfType(calleeType),
                        expr, expr.name, constructor,
                        expr.typeParameters, valueParameters
                    )
                }
            }
            is BinaryTypeOp -> return when (expr.op) {
                BinaryTypeOpType.INSTANCEOF -> BooleanType
                BinaryTypeOpType.CAST_OR_CRASH -> expr.right
                BinaryTypeOpType.CAST_OR_NULL -> unionTypes(expr.right, NullType)
            }
            is AssignIfMutableExpr -> return UnitType
            is CheckEqualsOp -> return BooleanType
            is PostfixExpression -> when (expr.type) {
                PostfixType.ASSERT_NON_NULL -> {
                    val type = resolveType(
                        /* just copying targetLambdaType is fine, is it? */
                        context, expr.base,
                    )
                    return removeNullFromType(type)
                }
                else -> TODO("Resolve type for PostfixExpression.${expr.type} in ${context.codeScope}, ${context.selfType}")
            }
            is ExpressionList -> {
                val lastExpr = expr.list.lastOrNull()
                return if (lastExpr != null) resolveType(context, lastExpr) else UnitType
            }
            is LambdaExpression -> {
                when (val targetLambdaType = context.targetLambdaType) {
                    is LambdaType -> {
                        // automatically add it...
                        if (expr.variables == null) {
                            expr.variables = when (val size = targetLambdaType.parameters.size) {
                                0 -> emptyList()
                                1 -> listOf(LambdaVariable(null, "it"))
                                else -> {
                                    // instead of throwing, we should probably just return some impossible type or error type...
                                    throw IllegalStateException("Found lambda without parameters, but expected $size")
                                }
                            }
                        }

                        check(expr.variables?.size == targetLambdaType.parameters.size)

                        val resolvedReturnType = /*resolveTypeGivenGenerics(
                            targetLambdaType.returnType,
                            targetLambdaType.parameters,
                            generics,
                        )*/ targetLambdaType.returnType
                        val parameters = expr.variables!!.mapIndexed { index, param ->
                            val type = param.type ?: targetLambdaType.parameters[index].type
                            LambdaParameter(param.name, type)
                        }
                        return LambdaType(parameters, resolvedReturnType)
                    }
                    null -> {
                        // else 'it' is not defined
                        if (expr.variables == null) expr.variables = emptyList()

                        val returnType = resolveType(
                            context.withTargetLambdaType(null),
                            expr.body,
                        )

                        return LambdaType(expr.variables!!.map {
                            LambdaParameter(it.name, it.type!!)
                        }, returnType)
                    }
                    else -> throw NotImplementedError("Extract LambdaType from $targetLambdaType")
                }
            }
            else -> TODO("Resolve type for ${expr.javaClass} in ${context.codeScope},${context.selfType}")
        }
    }

    private fun resolveThisType(scope: Scope): Scope {
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
            UnionType(type.types - NullType)
        } else type
    }

    private fun resolveCallType(
        context: ResolutionContext,
        expr: Expression,
        name: String,
        constructor: ResolvedConstructor?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): Type {
        println("typeParams: $typeParameters")
        val method = constructor
            ?: findMethod(context.selfScope, true, name, typeParameters, valueParameters)
            ?: findMethod(context.codeScope, true, name, typeParameters, valueParameters)
            ?: findMethod(langScope, false, name, typeParameters, valueParameters)
        val field = findField(context.codeScope, context.selfType, name)
        val candidates =
            listOfNotNull(method?.getTypeFromCall(), field?.resolveCall()?.getTypeFromCall())
        if (candidates.isEmpty()) {
            val selfScope = context.selfScope
            val codeScope = context.codeScope
            println("self-scope methods[${selfScope?.pathStr}.'$name']: ${selfScope?.methods?.filter { it.name == name }}")
            println("code-scope methods[${codeScope.pathStr}.'$name']: ${codeScope.methods.filter { it.name == name }}")
            println("lang-scope methods[${langScope.pathStr}.'$name']: ${langScope.methods.filter { it.name == name }}")
            throw IllegalStateException(
                "Could not resolve base '$name'<$typeParameters>($valueParameters) " +
                        "in ${resolveOrigin(expr.origin)}, scopes: ${codeScope.pathStr}"
            )
        }
        if (candidates.size > 1) throw IllegalStateException("Cannot have both a method and a type with the same name '$name': $candidates")
        return candidates.first()
    }

    fun findFieldType(base: Type, name: String): Type? {
        // todo field may be generic, inject the generics as needed...
        // todo check extension fields
        if (base is ClassType) {
            val fields = base.clazz.fields
            val field = fields.firstOrNull {
                it.name == name /*&& (it.selfType == null ||
                        it.selfType == base ||
                        it.selfType == base.clazz)*/
            }
            if (field != null) {
                if (field.valueType == null) {
                    val context = ResolutionContext(
                        field.declaredScope,
                        base, base.clazz,
                        false, null
                    )
                    field.valueType = resolveType(context, field.initialValue!!)
                }
                return field.valueType
            }
            if (base.clazz.scopeType == ScopeType.ENUM_CLASS) {
                val enumValues = base.clazz.enumValues
                if (enumValues.any { it.name == name }) {
                    return base.clazz.typeWithoutArgs
                }

                TODO("find child class")
            }
            // todo check super classes and interfaces
            println("No field matched: ${base.clazz.pathStr}.$name: ${fields.map { it.name }}")
        }
        TODO("findField($base, $name)")
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
            is Scope -> type
            // is NullableType -> typeToScope(type.base)
            else -> throw NotImplementedError("typeToScope($type)")
        }
    }

    fun findField(scope: Scope?, recursive: Boolean, name: String): Field? {
        var scope = scope
        while (scope != null) {
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

    interface Resolved {
        fun getTypeFromCall(): Type
    }

    class ResolvedField(val field: Field) : Resolved {
        override fun getTypeFromCall(): Type {
            TODO("Not yet implemented")
        }
    }

    class ResolvedConstructor(val constructor: Constructor, val generics: List<Type>) : Resolved {
        override fun getTypeFromCall(): Type {
            return if (generics.isEmpty()) constructor.clazz.typeWithoutArgs
            else ClassType(constructor.clazz, generics)
        }
    }

    class ResolvedMethod(val method: Method, val generics: List<Type>) : Resolved {
        override fun getTypeFromCall(): Type {
            return resolveTypeGivenGenerics(method.returnType!!, method.typeParameters, generics)
        }
    }

    fun resolveTypeGivenGenerics(type: Type, typeParams: List<Parameter>, generics: List<Type>): Type {
        when (type) {
            is GenericType -> {
                val idx = typeParams.indexOfFirst { it.name == type.name }
                if (idx >= 0) return generics[idx]
            }
            is UnionType -> {
                return type.types.map { partType ->
                    resolveTypeGivenGenerics(partType, typeParams, generics)
                }.reduce { a, b -> unionTypes(a, b) }
            }
            is ClassType -> {
                val typeArgs = type.typeArgs ?: return type
                val newTypeArgs = typeArgs.map { partType ->
                    resolveTypeGivenGenerics(partType, typeParams, generics)
                }
                return ClassType(type.clazz, newTypeArgs)
            }
            NullType -> return type
        }

        // todo we need nested resolution, going into all subtypes...
        return type
    }

    /**
     * finds a method, returns the method and any inserted type parameters
     * */
    fun findMethod(
        scope: Scope?, recursive: Boolean, name: String,
        typeParameters: List<Type>?, valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        var scope = scope ?: return null
        while (true) {
            for (method in scope.methods) {
                if (method.name != name) continue
                val generics = findGenericsForMatch(
                    method.typeParameters,
                    method.valueParameters,
                    typeParameters,
                    valueParameters
                ) ?: continue
                return ResolvedMethod(method, generics)
            }
            val newScope = if (recursive &&
                scope.scopeType != ScopeType.PACKAGE &&
                scope.scopeType != null
            ) {
                scope.parent
            } else null
            scope = newScope ?: return null
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

            val newScope = if (recursive &&
                scope.scopeType != ScopeType.PACKAGE &&
                scope.scopeType != null
            ) {
                scope.parent
            } else null
            scope = newScope ?: return null
        }
    }

    fun applyTypeAlias(
        typeParameters: List<Type>?,
        leftTypeParameters: List<Parameter>,
        rightType: Type
    ): ClassType {
        val rightType = if (rightType is Scope) ClassType(rightType, null) else rightType
        when (rightType) {
            is ClassType -> {
                if (leftTypeParameters.isEmpty()) {
                    check(typeParameters.isNullOrEmpty())
                    // no extra types get applied
                    return rightType
                }

                TODO("$typeParameters x $leftTypeParameters -> ${rightType.clazz.pathStr}<${rightType.typeArgs}>")
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
            println("  mapped ${scope.pathStr}<$typeParameters> via $alias to ${newType.clazz.pathStr}<${newType.typeArgs}>")
            return findConstructorImpl(newType.clazz, newType.typeArgs, valueParameters)
        }

        for (constructor in scope.constructors) {
            println("  candidate constructor: $constructor")
            val generics = findGenericsForMatch(
                constructor.clazz.typeParameters,
                constructor.valueParameters,
                typeParameters, valueParameters
            ) ?: continue
            return ResolvedConstructor(constructor, generics)
        }
        return null
    }

    fun findGenericsForMatch(
        // our method expects these:
        expectedTypeParameters: List<Parameter>,
        expectedValueParameters: List<Parameter>,
        // and given are these:
        actualTypeParameters: List<Type>?,
        actualValueParameters: List<ValueParameter>
    ): List<Type>? { // found generic values for a match

        println("checking $expectedTypeParameters vs $actualTypeParameters")
        println("     and $expectedValueParameters vs $actualValueParameters")

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

        val resolvedTypes =
            actualTypeParameters ?: ArrayList(List(expectedTypeParameters.size) { null })

        val findGenericTypes = actualTypeParameters == null

        for (i in expectedValueParameters.indices) {
            val mvParam = expectedValueParameters[i]
            val vParam = if (mvParam.isVararg) {

                val expectedParamArrayType = mvParam.type
                check(expectedParamArrayType is ClassType)
                check(expectedParamArrayType.clazz == ArrayType.clazz)
                check(expectedParamArrayType.typeArgs?.size == 1)
                val expectedParamEntryType = expectedParamArrayType.typeArgs[0]

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
                    findGenericTypes
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

    // todo resolve types step by step,
    //  not supporting any recursion, but being stable at least

}