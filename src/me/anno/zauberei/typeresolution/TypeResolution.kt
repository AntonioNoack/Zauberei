package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.members.MethodResolver.getMethodReturnType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType

/**
 * Resolve types step by step, might fail, but should be stable at least.
 * */
object TypeResolution {

    val langScope by lazy { Compile.root.getOrPut("zauber", null) }

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
            val initialValue = field.initialValue ?: field.getterExpr
            if (field.valueType == null && initialValue != null) {
                println("Resolving field $field in scope ${scope.pathStr}")
                println("fieldSelfType: ${field.selfType}")
                println("scopeSelfType: $scopeSelfType")
                //try {
                val selfType = field.selfType ?: scopeSelfType
                val context = ResolutionContext(field.declaredScope, selfType, false, null)
                field.valueType = resolveType(context, initialValue)
                println("Resolved $field to ${field.valueType}")
                /*} catch (e: Throwable) {
                    e.printStackTrace()
                    // continue anyway for now
                }*/
            }
        }
        if (false) println("${scope.fileName}: ${scope.pathStr}, ${scope.fields.size}f, ${scope.methods.size}m, ${scope.code.size}c")
    }

    fun getSelfType(scope: Scope): Type? {
        var scope = scope
        while (true) {
            val scopeType = scope.scopeType
            if (scopeType != null && scopeType.isClassType()) {
                val typeParams = scope.typeParameters.map { GenericType(scope, it.name) }
                return ClassType(scope, typeParams)
            }
            scope = scope.parent ?: return null
        }
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

    fun resolveValueParameters(
        context: ResolutionContext,
        base: List<NamedParameter>
    ): List<ValueParameter> {
        // target-type does not apply to parameters
        val contextWithoutTargetType = context.withTargetType(null)
        return base.map { param ->
            if (param.value.hasLambdaOrUnknownGenericsType()) {
                UnderdefinedValueParameter(param, contextWithoutTargetType)
            } else {
                val type = resolveType(contextWithoutTargetType, param.value)
                ValueParameterImpl(param.name, type)
            }
        }
    }

    fun resolveThisType(scope: Scope): Scope {
        var scope = scope
        while (true) {
            println("Checking ${scope.pathStr}/${scope.scopeType} for 'this'")
            val scopeType = scope.scopeType
            when {
                scopeType != null && scopeType.isClassType() -> return scope
                scopeType == ScopeType.METHOD -> {
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

    fun findType(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): ClassType? = findType(typeToScope(selfScope), name) ?: findType(scope, name)

    fun typeToScope(type: Type?): Scope? {
        return when (type) {
            null, NullType -> null
            is ClassType -> type.clazz
            is UnionType -> {
                val scopes = type.types.mapNotNull { typeToScope(it) }
                if (scopes.distinct().size == 1) scopes.first()
                else null
            }
            // is NullableType -> typeToScope(type.base)
            else -> throw NotImplementedError("typeToScope($type)")
        }
    }

    fun findType(scope: Scope?, name: String): ClassType? {
        var scope = scope ?: return null
        while (true) {
            val match = scope.children.firstOrNull { it.name == name }
            if (match != null) return ClassType(match, null)
            scope = scope.parentIfSameFile ?: return null
        }
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

}