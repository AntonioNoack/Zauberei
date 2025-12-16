package me.anno.zauberei.typeresolution.linear

import me.anno.zauberei.Compile
import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.CallExpression
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.NamedCallExpression
import me.anno.zauberei.astbuilder.expression.VariableExpression
import me.anno.zauberei.astbuilder.expression.constants.Constant
import me.anno.zauberei.astbuilder.expression.constants.ConstantExpression
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.WhileLoop
import me.anno.zauberei.typeresolution.graph.TypeResolution.forEachScope
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.Types.UnitType
import me.anno.zauberei.types.UnionType.Companion.unionTypes

/**
 * Resolve types step by step.
 * -> good idea, but Kotlin is more complex :(
 * todo -> simple sample, where it fails:
 * fun execute(args: List<String>); execute(emptyList())
 * -> emptyList() - type depends on what it's used for
 * -> todo we need a LazyType -> matchesType() depends on what it's used for :)
 * */
object LinearTypeResolution {

    val langScope by lazy { Compile.root.getOrPut("zauberKt", null) }

    class ValueParameter(val name: String?, val type: Type) {
        override fun toString(): String {
            return if (name != null) "$name=($type)"
            else "($type)"
        }
    }

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::resolveTypesAndNamesImpl)
    }

    fun resolveTypesAndNamesImpl(scope: Scope) {
        for (method in scope.methods) {
            resolveMethodReturnType(method)
        }
        println("${scope.fileName}: ${scope.pathStr}, ${scope.fields.size}f, ${scope.methods.size}m, ${scope.code.size}c")
    }

    fun resolveMethodReturnType(method: Method): Type {
        if (method.returnType == null) {
            println("Resolving ${method.innerScope}.type by ${method.body}")
            method.returnType = resolveType(
                method.innerScope,
                method.selfType, typeToScope(method.selfType),
                method.body!!, false
            )
        }
        return method.returnType!!
    }

    fun resolveFieldType(field: Field): Type {
        if (field.valueType == null) {
            field.valueType = resolveType(
                field.declaredScope,
                field.selfType, typeToScope(field.selfType),
                field.initialValue!!, false
            )
        }
        return field.valueType!!
    }

    /**
     * resolve the type for a given expression;
     * an expression can be
     * */
    fun resolveType(
        scope: Scope, // 3rd
        selfType: Type?, // 2nd
        selfScope: Scope?,
        expr: Expression,
        allowTypeless: Boolean
    ): Type {
        // if already resolved, just use that type
        val alreadyResolved = expr.resolvedType
        if (alreadyResolved != null) {
            return alreadyResolved
        } else {
            val type = resolveTypeImpl(scope, selfType, selfScope, expr, allowTypeless)
            println("Resolved type of $expr to $type")
            expr.resolvedType = type
            return type
        }
    }

    /**
     * resolve the type for a given expression;
     * an expression can be
     * */
    fun resolveTypeImpl(
        scope: Scope, // 3rd
        selfType: Type?, // 2nd
        selfScope: Scope?,
        expr: Expression,
        allowTypeless: Boolean
    ): Type {
        when (expr) {
            is CallExpression -> {
                val typeParameters = expr.typeParameters
                val valueParameters = expr.valueParameters.map { param ->
                    val type = resolveType(scope, selfType, selfScope, param.value, false)
                    ValueParameter(param.name, type)
                }
                println("Resolving call: ${expr.base}<${typeParameters ?: "?"}>($valueParameters)")
                // todo base can be a constructor, field or a method
                // todo find the best matching candidate...
                val base = expr.base
                when {
                    base is NamedCallExpression && base.name == "." -> {
                        TODO("Find method/field ${expr.base}($valueParameters)")
                    }
                    base is VariableExpression -> {
                        val name = base.name
                        // findConstructor(selfScope, false, name, typeParameters, valueParameters)
                        val method =
                            findConstructor(base.nameAsImport, false, name, typeParameters, valueParameters)
                                ?: findConstructor(scope, true, name, typeParameters, valueParameters)
                                ?: findMethod(selfScope, true, name, typeParameters, valueParameters)
                                ?: findMethod(scope, true, name, typeParameters, valueParameters)
                                ?: findMethod(langScope, false, name, typeParameters, valueParameters)
                        val field = findField(scope, selfType, name)
                        val candidates =
                            listOfNotNull(method?.getTypeFromCall(), field?.resolveCall()?.getTypeFromCall())
                        if (candidates.isEmpty()) {
                            println("lang-scope methods: ${langScope.methods}")
                            throw IllegalStateException(
                                "Could not resolve base '$name'<$typeParameters>($valueParameters) " +
                                        "in ${resolveOrigin(expr.origin)}, scopes: ${scope.pathStr}"
                            )
                        }
                        if (candidates.size > 1) throw IllegalStateException("Cannot have both a method and a type with the same name '$name': $candidates")
                        return candidates.first()
                    }
                    else -> throw IllegalStateException(
                        "Resolve field/method for ${base.javaClass} ($base) " +
                                "in ${resolveOrigin(expr.origin)}"
                    )
                }
            }
            is VariableExpression -> {
                val field = findField(scope, selfType, expr.name)
                    ?: throw IllegalStateException(
                        "Missing field '${expr.name}' in $scope,$selfType, " +
                                resolveOrigin(expr.origin)
                    )
                return resolveFieldType(field)
            }
            is WhileLoop -> {
                if (!allowTypeless)
                    throw IllegalStateException("Expected type, but found while-loop")
                return UnitType
            }
            is IfElseBranch -> {
                if (expr.elseBranch == null && !allowTypeless)
                    throw IllegalStateException("Expected type, but found if without else")
                if (expr.elseBranch == null) return UnitType
                val ifType = resolveType(scope, selfType, selfScope, expr.ifBranch, allowTypeless)
                val elseType = resolveType(scope, selfType, selfScope, expr.elseBranch, allowTypeless)
                return typeAAndTypeB(ifType, elseType)
            }
            is ConstantExpression -> {
                when (expr.value) {
                    Constant.NULL -> return NullType
                    else -> TODO("Resolve type for ConstantExpression in $scope,$selfType")
                }
            }
            else -> TODO("Resolve type for ${expr.javaClass} in $scope,$selfType")
        }
    }

    fun typeAAndTypeB(typeA: Type, typeB: Type): Type {
        if (typeA == typeB) return typeA
        TODO("Result must be of $typeA and $typeB, laziest solution: Any?")
    }

    fun findField(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): Field? = findField(typeToScope(selfScope), false, name) ?: findField(scope, true, name)

    fun Field.resolveCall(): ResolvedField {
        return ResolvedField(this)
    }

    fun typeToScope(type: Type?): Scope? {
        return when (type) {
            null -> null
            is ClassType -> type.clazz
            // is NullableType -> typeToScope(type.base)
            else -> throw NotImplementedError()
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
            return if (generics.isEmpty()) constructor.clazz
            else ClassType(constructor.clazz, generics)
        }
    }

    class ResolvedMethod(val method: Method, val generics: List<Type>) : Resolved {
        override fun getTypeFromCall(): Type {
            return resolveTypeGivenGenerics(method.returnType!!, method.typeParameters, generics)
        }
    }

    fun resolveTypeGivenGenerics(type: Type, typeParams: List<Parameter>, generics: List<Type>): Type {
        if (type is GenericType) {
            val idx = typeParams.indexOfFirst { it.name == type.name }
            if (idx >= 0) return generics[idx]
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
        var scope = scope
        while (scope != null) {
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
            scope = if (recursive && scope.parent?.fileName == scope.fileName) {
                scope.parent
            } else null
        }
        return null
    }

    fun findConstructor(
        scope: Scope?, recursive: Boolean, name: String,
        typeParameters: List<Type>?, valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        var scope = scope
        while (scope != null) {
            if (scope.name == name) {
                for (constructor in scope.constructors) {
                    val generics = findGenericsForMatch(
                        constructor.typeParameters,
                        constructor.valueParameters,
                        typeParameters, valueParameters
                    ) ?: continue
                    return ResolvedConstructor(constructor, generics)
                }
            }
            scope = if (recursive && scope.parent?.fileName == scope.fileName) {
                scope.parent
            } else null
        }
        return null
    }

    fun findGenericsForMatch(
        methodTypeParameters: List<Parameter>,
        methodValueParameters: List<Parameter>,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): List<Type>? { // found generic values for a match

        println("checking $methodTypeParameters vs $typeParameters")
        println("     and $methodValueParameters vs $valueParameters")

        // first match everything by name
        // todo resolve default values... -> could be expanded earlier :)
        // todo resolve varargs...

        val isVararg = methodValueParameters.lastOrNull()?.isVararg == true
        if (isVararg) {
            if (methodValueParameters.size > valueParameters.size) {
                println("param-size too low")
                return null
            }
        } else {
            if (methodValueParameters.size != valueParameters.size) {
                println("param-size mismatch: expected ${methodValueParameters.size}, but got ${valueParameters.size}")
                return null
            }
        }

        if (typeParameters != null && typeParameters.size != methodTypeParameters.size) {
            println("type-param-size mismatch")
            return null
        }

        val sortedValueParameters = resolveNamedParameters(methodValueParameters, valueParameters)
            ?: run {
                println("param-name mismatch")
                return null
            }

        val resolvedTypes =
            typeParameters ?: ArrayList(List(methodTypeParameters.size) { null })

        val findGenericTypes = typeParameters == null

        for (i in methodValueParameters.indices) {
            val mvParam = methodValueParameters[i]
            val vParam = if (mvParam.isVararg) {
                // if star, use it as-is
                val commonType = sortedValueParameters.subList(i, sortedValueParameters.size)
                    .map { it.type }
                    .reduce { a, b -> unionTypes(a, b) }
                val joinedType = ClassType(ArrayType.clazz, listOf(commonType))
                ValueParameter(null, joinedType)
            } else {
                sortedValueParameters[i]
            }
            if (!isSubTypeOf(
                    mvParam, vParam,
                    methodTypeParameters,
                    resolvedTypes,
                    findGenericTypes
                )
            ) {
                println("type mismatch: ${vParam.type} is not always a ${mvParam.type}")
                return null
            }
        }

        // todo check that the types are compatible; if they are generics, reduce their type appropriately
        // todo handle all remaining parameters by index, last one may be varargs

        return resolvedTypes as List<Type>
    }

    fun isSubTypeOf(
        expected: Parameter,
        actual: ValueParameter,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        findGenericTypes: Boolean
    ): Boolean {
        return isSubTypeOf(
            expected.type, actual.type,
            expectedTypeParams, actualTypeParameters,
            true,
            findGenericTypes
        )
    }

    fun isSubTypeOf(
        expectedType: Type,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertTypes: Boolean,
        findGenericTypes: Boolean
    ): Boolean {
        if (expectedType == actualType) return true
        if (actualType is UnionType) {
            // everything must fit
            val t0 = actualType.types.all { allActual ->
                isSubTypeOf(
                    expectedType, allActual,
                    expectedTypeParams,
                    actualTypeParameters,
                    false,
                    findGenericTypes
                )
            }
            if (t0 || !insertTypes) return t0
            return actualType.types.all { allActual ->
                isSubTypeOf(
                    expectedType, allActual,
                    expectedTypeParams,
                    actualTypeParameters,
                    true,
                    findGenericTypes
                )
            }
        }
        if (expectedType is UnionType) {
            val t0 = expectedType.types.any { anyExpected ->
                isSubTypeOf(
                    anyExpected, actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    false,
                    findGenericTypes
                )
            }
            if (t0 || !insertTypes) return t0
            return expectedType.types.any { anyExpected ->
                isSubTypeOf(
                    anyExpected, actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    true,
                    findGenericTypes
                )
            }
        }

        if (actualType is GenericType) {
            TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
        }

        if (expectedType is GenericType) {
            if (findGenericTypes) {
                val typeParamIdx = expectedTypeParams.indexOfFirst { it.name == expectedType.name }
                actualTypeParameters as MutableList<Type?>

                val actualTypeParam = actualTypeParameters[typeParamIdx]
                val expectedTypeParam = expectedTypeParams[typeParamIdx]
                if (!isSubTypeOf( // subtype not fulfilled
                        expectedTypeParam.type,
                        actualType,
                        expectedTypeParams,
                        actualTypeParameters,
                        insertTypes = false, findGenericTypes = false
                    )
                ) return false

                if (actualTypeParam != null && actualTypeParam != actualType) {
                    actualTypeParameters[typeParamIdx] = unionTypes(actualTypeParam, actualType)
                }
                return true

            } else TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
        }

        if ((expectedType is NullType) != (actualType is NullType)) {
            return false
        }

        val expectedType = if (expectedType is Scope) ClassType(expectedType, null) else expectedType
        val actualType = if (actualType is Scope) ClassType(actualType, null) else actualType

        if (actualType is ClassType && expectedType is ClassType) {
            // todo check generics
            val actualGenerics = actualType.typeArgs
            val expectedGenerics = expectedType.typeArgs
            val size0 = actualGenerics?.size ?: 0
            val size1 = expectedGenerics?.size ?: 0
            if (!(size0 == 0 && size1 == 0)) {
                if (actualGenerics != null && expectedGenerics != null &&
                    actualGenerics.size != expectedGenerics.size
                ) {
                    // should not happen, I think
                    return false
                }
                if (actualGenerics != null && expectedGenerics != null) {
                    TODO("Compare all generics...")
                }
                TODO("Compare generics $expectedGenerics vs $actualGenerics")
            }

            println("classType of $expectedType: ${expectedType.clazz.scopeType}")
            return when (expectedType.clazz.scopeType) {
                ScopeType.INTERFACE -> {
                    TODO("check super interfaces of $actualType for $expectedType")
                }
                ScopeType.NORMAL_CLASS -> {
                    TODO("check super classes of $actualType for $expectedType")
                }
                ScopeType.INLINE_CLASS -> false
                ScopeType.ENUM_CLASS -> false
                ScopeType.OBJECT -> false
                else -> false
            }
        }

        TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
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