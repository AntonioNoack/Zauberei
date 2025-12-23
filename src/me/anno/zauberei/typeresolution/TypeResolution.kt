package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile
import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.expression.ExprTypeOp
import me.anno.zauberei.astbuilder.expression.ExprTypeOpType
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.FieldExpression
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.AndType.Companion.andTypes
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType
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
                val selfType = field.selfType ?: scopeSelfType
                val context = ResolutionContext(field.declaredScope, selfType, false, null)
                field.valueType = resolveType(context, field.initialValue)
                println("Resolved $field to ${field.valueType}")
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

    fun resolveFieldType(field: Field, scope: Scope, targetType: Type?): Type {
        val base = field.selfType ?: getSelfType(field.declaredScope)
        return resolveFieldType(base, field, scope, targetType)
    }

    fun resolveFieldType(base: Type?, field: Field, scope: Scope, targetType: Type?): Type {
        println("InitialType[${field.declaredScope}.${field.name}]: ${field.valueType}")
        val fieldType0 = if (targetType == null) field.valueType else null
        var fieldType = fieldType0 ?: run {
            val context = ResolutionContext(field.declaredScope, base, false, targetType)
            val initialValue = field.initialValue
            val getter = field.getterExpr
            when {
                initialValue != null -> resolveType(context, initialValue)
                getter != null -> resolveType(context, getter)
                else -> throw IllegalStateException("Missing initial value or getter for $field")
            }
        }
        if (targetType == null) {
            field.valueType = fieldType
        }

        // todo valueType is just the general type, there might be much more specific information...
        // todo if the variable is re-assigned, these conditions no longer hold
        println("GeneralFieldType[${field.declaredScope}.${field.name}]: $fieldType in scope ${scope.pathStr}")

        // todo remove debug check when it works
        if (field.declaredScope.name == "/ disable if case /" &&
            field.name == "valueParameters" && fieldType is ClassType &&
            (fieldType.typeParameters?.getOrNull(0) as? ClassType)?.clazz?.name == "NamedParameter"
        ) {
            throw IllegalStateException("Field ${field.name} should be List<ValueParameter>, not List<NamedParameter>")
        }

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
                        var nameExpr = condition.left as? FieldExpression
                        val type = condition.right
                        var matchesName = false
                        while (!matchesName && nameExpr != null) { // todo check if field is actually the same
                            matchesName = nameExpr.field.name == field.name
                            nameExpr = nameExpr.field.initialValue as? FieldExpression
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

        println("SpecializedFieldType[${field.declaredScope}.${field.name}]: $fieldType")

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

    fun List<Type>.reduceUnionOrNull(): Type? = reduceOrNull { a, b -> unionTypes(a, b) }

    fun findType(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): ClassType? = findType(typeToScope(selfScope), name) ?: findType(scope, name)

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