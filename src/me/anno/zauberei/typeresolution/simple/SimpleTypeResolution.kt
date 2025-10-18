package me.anno.zauberei.typeresolution.simple

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.TokenListIndex
import me.anno.zauberei.astbuilder.expression.CallExpression
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.NamedCallExpression
import me.anno.zauberei.astbuilder.expression.VariableExpression
import me.anno.zauberei.typeresolution.complex.TypeResolution.forEachScope
import me.anno.zauberei.types.*

object SimpleTypeResolution {

    // done param-scope should be parent of body-scope & define params as fields
    // todo primary constructor should be its own space for primary-constructor-parameters

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
                method.innerScope, method.selfType,
                method.body!!, false
            )
        }
        return method.returnType!!
    }

    fun resolveFieldType(field: Field): Type {
        if (field.valueType == null) {
            field.valueType = resolveType(
                field.declaredScope,
                field.selfType,
                field.initialValue!!,
                false
            )
        }
        return field.valueType!!
    }

    fun resolveType(
        scope: Scope, // 3rd
        selfScope: Type?, // 2nd
        expr: Expression,
        allowTypeless: Boolean
    ): Type {
        if (expr.resolvedType != null) return expr.resolvedType!!
        when (expr) {
            is CallExpression -> {
                val types = expr.valueParameters.map { param ->
                    resolveType(scope, selfScope, param.value, false)
                }
                // todo base can be a field or a method
                val base = expr.base
                when {
                    base is NamedCallExpression && base.name == "." -> {

                    }
                    else -> throw IllegalStateException(
                        "Resolve field/method for $base, " +
                                TokenListIndex.resolve(expr.origin)
                    )
                }

                TODO("Find method ${expr.base}($types)")
            }
            is VariableExpression -> {
                val field = findField(scope, selfScope, expr.name)
                    ?: throw IllegalStateException(
                        "Missing field '${expr.name}' in $scope,$selfScope, " +
                                TokenListIndex.resolve(expr.origin)
                    )
                return resolveFieldType(field)
            }
            else -> TODO("Resolve type for ${expr.javaClass} in $scope,$selfScope")
        }
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

    // todo resolve types step by step,
    //  not supporting any recursion, but being stable at least

}