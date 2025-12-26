package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.typeresolution.TypeResolution.getSelfType
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.reduceUnionOrNull
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.typeresolution.TypeResolution.typeToScope
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.*
import me.anno.zauberei.types.impl.AndType.Companion.andTypes

object ResolveField {

    fun findField(scope: Scope?, name: String): Field? {
        var scope = scope ?: return null
        while (true) {

            if (scope.scopeType == ScopeType.OBJECT && scope.name == name) {
                return scope.objectField!!
            }

            val child = scope.children.firstOrNull { it.name == name }
            if (child != null && child.scopeType == ScopeType.OBJECT) {
                return child.objectField!!
            }

            val match = scope.fields.firstOrNull { it.name == name }
            if (match != null) return match

            scope = scope.parentIfSameFile ?: return null
        }
    }

    fun findField(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): Field? = findField(typeToScope(selfScope), name) ?: findField(scope, name)

    fun findExtensionField(
        base: ClassType, name: String, generics: List<Type>,
        scope: Scope
    ): Field? {
        var scope = scope
        while (true) {
            val field = scope.fields
                .firstOrNull { it.matches(base, name, generics) }
            if (field != null) return field

            scope = scope.parentIfSameFile ?: return null
        }
    }

    fun findField(
        base: ClassType, name: String, generics: List<Type>,
        codeScope: Scope, origin: Int
    ): Field? {
        // todo field may be generic, inject the generics as needed...
        // todo check extension fields

        val fields = base.clazz.fields
        val field = fields.firstOrNull { it.matches(base, name, generics) }
        if (field != null) return field

        if (base.clazz.scopeType == ScopeType.ENUM_CLASS) {
            val enumValues = base.clazz.enumValues
            val enumValue = enumValues.firstOrNull { it.name == name }
            if (enumValue != null) {
                return enumValue.scope.objectField!!
            }
        }

        // check super classes and interfaces,
        //  but we need their generics there...
        // -> interfaces can define the field, but it always needs to be in a class, too, so just check super class
        val superCalls = base.clazz.superCalls
        for (i in superCalls.indices) {
            val superClass = superCalls[i].type as ClassType
            val bySuper = findFieldBySuperClass(base, name, generics, codeScope, origin, superClass)
            if (bySuper != null) return bySuper
        }

        // we must also check related scopes for extension fields;
        // since we check super calls recursively, it is fine to only check for type-equals;
        // scopes to check:
        //  - langScope
        //  - codeScope (same file)
        val type = findExtensionField(base, name, generics, codeScope)
            ?: findExtensionField(base, name, generics, langScope)
        if (type != null) return type

        println("No field matched: ${base.clazz.pathStr}.$name: ${fields.map { it.name }}")
        return null
    }

    fun findFieldType(
        base: Type, name: String, generics: List<Type>,
        codeScope: Scope, origin: Int,
        targetType: Type?
    ): Type? {
        // todo field may be generic, inject the generics as needed...
        // todo check extension fields
        return when (base) {
            NullType, is NotType -> null
            is ClassType -> {
                val field = findField(base, name, generics, codeScope, origin)
                if (field != null) return resolveFieldType(field, codeScope, targetType)

                println("No field matched: ${base.clazz.pathStr}.$name")
                null
            }
            is UnionType -> {
                base.types.mapNotNull { subType ->
                    findFieldType(subType, name, generics, codeScope, origin, targetType)
                }.reduceUnionOrNull() // union or and?
            }
            is AndType -> {
                base.types.mapNotNull { subType ->
                    findFieldType(subType, name, generics, codeScope, origin, targetType)
                }.reduceUnionOrNull()
            }
            else -> throw NotImplementedError("findFieldType($base, $name) @ ${resolveOrigin(origin)}")
        }
    }

    private fun findFieldBySuperClass(
        base: ClassType, name: String, generics: List<Type>,
        scope: Scope, origin: Int, superClass: ClassType
    ): Field? {
        val superGenerics = superClass.typeParameters ?: emptyList()
        val genericNames = base.clazz.typeParameters
        return findField(superClass, name, superGenerics.map { type ->
            resolveGenerics(type, genericNames, generics)
        }, scope, origin)
    }

    private fun Field.matches(
        expectedSelfType: ClassType,
        fieldName: String,
        generics: List<Type>
    ): Boolean {
        return name == fieldName &&
                selfType != null &&
                isSubTypeOf(
                    expectedSelfType, selfType,
                    expectedSelfType.clazz.typeParameters, generics,
                    InsertMode.READ_ONLY
                )
    }

    fun resolveFieldType(field: Field, scope: Scope, targetType: Type?): Type {
        val base = field.selfType ?: getSelfType(field.declaredScope)
        return resolveFieldType(base, field, scope, targetType)
    }

    fun resolveFieldType(selfType: Type?, field: Field, scope: Scope, targetType: Type?): Type {
        println("InitialType[${field.declaredScope}.${field.name}]: ${field.valueType}")
        val fieldType0 = field.valueType // if (targetType == null) field.valueType else null
        var fieldType = fieldType0 ?: run {
            val context = ResolutionContext(field.declaredScope, selfType, false, targetType)
            val initialValue = field.initialValue
            val getter = field.getterExpr
            when {
                initialValue != null -> resolveType(context, initialValue)
                getter != null -> resolveType(context, getter)
                else -> throw IllegalStateException("Missing initial value or getter for type resolution of $field")
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
                var conditionType = matchesField(condition, field, selfType)

                if (conditionType != null) {
                    if (!scopeI.branchConditionTrue) {
                        conditionType = conditionType.not()
                    }
                    fieldType = andTypes(fieldType, conditionType)
                    println("  -> $fieldType (via $conditionType)")
                } else println("  -> condition not yet supported or not relevant (${condition.javaClass.simpleName})")
            }
            scopeI = scopeI.parent ?: break
        }

        println("SpecializedFieldType[${field.declaredScope}.${field.name}]: $fieldType")

        return fieldType
    }

    fun matchesField(expr: Expression, field: Field, selfType: Type?): Type? {
        return when (expr) {
            is ExprTypeOp -> {
                val type = expr.right
                if (matchesFieldI(expr, field)) {
                    when (expr.op) {
                        ExprTypeOpType.INSTANCEOF -> type
                        ExprTypeOpType.NOT_INSTANCEOF -> type.not()
                        else -> null
                    }
                } else null
            }
            is CheckEqualsOp -> {
                val context = ResolutionContext(
                    expr.scope, selfType,
                    false, null
                )
                val baseType = when {
                    matchesFieldI(expr.right, field) -> resolveType(context, expr.left)
                    matchesFieldI(expr.left, field) -> resolveType(context, expr.right)
                    else -> null
                }
                println(
                    "CheckEqualsOp[${field.name}]: $baseType, " +
                            "${expr.left.javaClass.simpleName}, ${expr.right.javaClass.simpleName}"
                )
                if (baseType != null) {
                    if (expr.negated) {
                        // if enum with single value or object,
                        //  we can invert the type, otherwise not
                        if (baseType == NullType ||
                            (baseType is ClassType && baseType.clazz.scopeType == ScopeType.OBJECT) ||
                            (baseType is ClassType && baseType.clazz.scopeType == ScopeType.ENUM_CLASS &&
                                    baseType.clazz.enumValues.size == 1)
                        ) {
                            baseType.not()
                        } else null
                    } else baseType
                } else null
            }
            else -> null
        }
    }

    fun matchesFieldI(expr: Expression, field: Field): Boolean {
        return when (expr) {
            is NameExpression -> {
                // todo expression should have been replaced as a field from the start
                expr.name == field.name
            }
            is FieldExpression -> {
                if (expr.field == field) true
                else {
                    val prevExpr = expr.field.initialValue ?: return false
                    matchesFieldI(prevExpr, field)
                }
            }
            else -> false
        }
    }

}