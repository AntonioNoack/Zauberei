package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.GenericType
import me.anno.zauberei.types.NullType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.UnionType
import me.anno.zauberei.types.UnionType.Companion.unionTypes

/**
 * Check if one type inherits from another, incl. generic checks.
 * */
object Inheritance {

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
        println("checking $actualType instanceOf $expectedType")
        val result = isSubTypeOfImpl(
            expectedType,
            actualType,
            expectedTypeParams,
            actualTypeParameters,
            insertTypes,
            findGenericTypes
        )
        println("got $result for $actualType instanceOf $expectedType")
        return result
    }

    private fun isSubTypeOfImpl(
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
            // first try without inserting types
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
            // then, try with inserting new types
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
            // first try without inserting types
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
            // then, try with inserting new types
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

                actualTypeParameters[typeParamIdx] = if (actualTypeParam != null && actualTypeParam != actualType) {
                    unionTypes(actualTypeParam, actualType)
                } else {
                    actualTypeParam
                }
                println("set types[$typeParamIdx] = ${actualTypeParameters[typeParamIdx]}")
                return true

            } else TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
        }

        if ((expectedType == NullType) != (actualType == NullType)) {
            return false
        }

        val expectedType = if (expectedType is Scope) ClassType(expectedType, null) else expectedType
        val actualType = if (actualType is Scope) ClassType(actualType, null) else actualType
        println(
            "checking-1: $expectedType<${(expectedType as? ClassType)?.typeArgs}> vs " +
                    "$actualType<${(actualType as? ClassType)?.typeArgs}> " +
                    "-> ${expectedType == actualType}"
        )
        if (expectedType == actualType ||
            (expectedType is ClassType && actualType is ClassType &&
                    expectedType.clazz == actualType.clazz &&
                    (expectedType.typeArgs?.size ?: 0) == (actualType.typeArgs?.size ?: 0))
        ) return true

        if (actualType is ClassType && expectedType is ClassType) {
            // todo check generics
            val actualGenerics = actualType.typeArgs
            val expectedGenerics = expectedType.typeArgs
            val size0 = actualGenerics?.size ?: 0
            val size1 = expectedGenerics?.size ?: 0
            if (!(size0 == 0 && size1 == 0) &&
                expectedType.clazz == actualType.clazz
            ) {
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
                    // check super class
                    // todo if super type has generics, we need to inject them into the super type
                    val superType = actualType.clazz.superCalls.firstOrNull { it.params != null }?.type ?: AnyType.clazz
                    if (superType != actualType.clazz) println("super($actualType): $superType")
                    (superType != actualType.clazz) && isSubTypeOf(
                        expectedType,
                        superType,
                        expectedTypeParams,
                        actualTypeParameters,
                        insertTypes,
                        findGenericTypes
                    )
                }
                ScopeType.INLINE_CLASS -> false
                ScopeType.ENUM_CLASS -> false
                ScopeType.OBJECT -> false
                else -> false
            }
        }

        TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
    }

}