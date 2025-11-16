package me.anno.zauberei.typeresolution.linear

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.TokenListIndex
import me.anno.zauberei.astbuilder.expression.CallExpression
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.NamedCallExpression
import me.anno.zauberei.astbuilder.expression.VariableExpression
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.WhileLoop
import me.anno.zauberei.typeresolution.graph.TypeResolution.forEachScope
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.UnitType

/**
 * Resolve types step by step.
 * */
object LinearTypeResolution {

    class ValueParameter(val name: String?, val type: Type)

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::resolveTypesAndNamesImpl)
    }

    fun resolveTypesAndNamesImpl(scope: Scope) {
        for (method in scope.methods) {
            resolveMethodReturnType(method)
        }
        println("${scope.fileName}: ${scope.path.joinToString(".")}, ${scope.fields.size}f, ${scope.methods.size}m, ${scope.code.size}c")
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

    fun resolveType(
        scope: Scope, // 3rd
        selfType: Type?, // 2nd
        selfScope: Scope?,
        expr: Expression,
        allowTypeless: Boolean
    ): Type {
        if (expr.resolvedType != null) return expr.resolvedType!!
        when (expr) {
            is CallExpression -> {
                val typeParameters = expr.typeParameters
                val valueParameters = expr.valueParameters.map { param ->
                    val type = resolveType(scope, selfType, selfScope, param.value, false)
                    ValueParameter(param.name, type)
                }
                // todo base can be a constructor, field or a method
                // todo find the best matching candidate...
                val base = expr.base
                when {
                    base is NamedCallExpression && base.name == "." -> {

                    }
                    base is VariableExpression -> {
                        val name = base.name
                        findConstructor(selfScope, false, name, typeParameters, valueParameters)
                        findConstructor(scope, true, name, typeParameters, valueParameters)
                            ?: findMethod(selfScope, true, name, typeParameters, valueParameters)
                            ?: findMethod(scope, true, name, typeParameters, valueParameters)
                            ?: findField(scope, selfType, name)
                    }
                    else -> throw IllegalStateException(
                        "Resolve field/method for ${base.javaClass} ($base), " +
                                TokenListIndex.resolve(expr.origin)
                    )
                }

                TODO("Find method ${expr.base}($valueParameters)")
            }
            is VariableExpression -> {
                val field = findField(scope, selfType, expr.name)
                    ?: throw IllegalStateException(
                        "Missing field '${expr.name}' in $scope,$selfType, " +
                                TokenListIndex.resolve(expr.origin)
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

    fun typeToScope(type: Type?): Scope? {
        return when (type) {
            null -> null
            is ClassType -> type.clazz
            is NullableType -> typeToScope(type.base)
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

    fun findMethod(
        scope: Scope?, recursive: Boolean, name: String,
        typeParameters: List<Type>?, valueParameters: List<ValueParameter>
    ): Pair<Method, List<Type>>? {
        var scope = scope
        while (scope != null) {
            val match = scope.methods.firstNotNullOfOrNull { method ->
                if (method.name == name) {
                    val matchingGenerics = findGenericsForMatch(
                        method.typeParameters,
                        method.valueParameters,
                        typeParameters,
                        valueParameters
                    )
                    if (matchingGenerics != null) {
                        method to matchingGenerics
                    } else null
                } else null
            }
            if (match != null) return match
            scope = if (recursive && scope.parent?.fileName == scope.fileName) {
                scope.parent
            } else null
        }
        return null
    }

    fun findConstructor(
        scope: Scope?, recursive: Boolean, name: String,
        typeParameters: List<Type>?, valueParameters: List<ValueParameter>
    ): Pair<Constructor, List<Type>>? {
        var scope = scope
        while (scope != null) {
            if (scope.name == name) {
                val match = scope.constructors.firstNotNullOfOrNull { constructor ->
                    val generics = findGenericsForMatch(
                        constructor.typeParameters,
                        constructor.valueParameters,
                        typeParameters, valueParameters
                    )
                    if (generics != null) {
                        constructor to generics
                    } else null
                }
                if (match != null) return match
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

        // first match everything by name
        for (param in valueParameters) {
            if (param.name == null) continue
            val methodParam = methodValueParameters.firstOrNull { it.name == param.name }
                ?: return null
            // todo check that the types are compatible; if they are generics, reduce their type appropriately
        }

        // todo handle all remaining parameters by index, last one may be varargs

        return null
    }

    // todo resolve types step by step,
    //  not supporting any recursion, but being stable at least

}