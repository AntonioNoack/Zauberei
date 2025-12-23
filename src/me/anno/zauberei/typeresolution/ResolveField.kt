package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.reduceUnionOrNull
import me.anno.zauberei.typeresolution.TypeResolution.resolveFieldType
import me.anno.zauberei.typeresolution.TypeResolution.typeToScope
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.AndType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.NotType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType

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

}