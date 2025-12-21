package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.LambdaType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType

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
            expected.type,
            actual.getType(expected.type),
            expectedTypeParams, actualTypeParameters,
            true, findGenericTypes
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

        println(
            "checking-1: $expectedType<${(expectedType as? ClassType)?.typeParameters}> vs " +
                    "$actualType<${(actualType as? ClassType)?.typeParameters}> " +
                    "-> ${expectedType == actualType}"
        )

        if (expectedType == actualType ||
            (expectedType is ClassType && actualType is ClassType &&
                    expectedType.clazz == actualType.clazz &&
                    (expectedType.typeParameters?.size ?: 0) == (actualType.typeParameters?.size ?: 0))
        ) return true

        if (actualType is ClassType && expectedType is ClassType) {
            // todo check generics
            val actualGenerics = actualType.typeParameters
            val expectedGenerics = expectedType.typeParameters
            val size0 = actualGenerics?.size ?: 0
            val size1 = expectedGenerics?.size ?: 0
            if (!(size0 == 0 && size1 == 0) &&
                expectedType.clazz == actualType.clazz
            ) {
                if (actualGenerics != null && expectedGenerics != null) {
                    // should not happen, I think
                    return false
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
                    val superType = actualType.clazz.superCalls.firstOrNull { it.valueParams != null }?.type ?: AnyType
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

        if ((actualType is LambdaType) != (expectedType is LambdaType)) {
            return false
        }

        if (actualType is LambdaType && expectedType is LambdaType) {
            if (expectedType.parameters.size != actualType.parameters.size) return false

            return isSubTypeOf(
                // todo return type is one direction, actual type is the other...
                //  params are normal, return type is the other way around...
                //  -> this needs to be flipped, but I don't really know what
                //         [expectedTypeParams, actualTypeParameters, insertTypes, findGenericTypes] is,
                //     and how we're supposed to replace them
                expectedType.returnType, actualType.returnType,
                expectedTypeParams, actualTypeParameters,
                insertTypes, findGenericTypes
            ) && expectedType.parameters.indices.all { paramIndex ->
                isSubTypeOf(
                    expectedType.parameters[paramIndex].type,
                    actualType.parameters[paramIndex].type,
                    expectedTypeParams, actualTypeParameters,
                    insertTypes, findGenericTypes
                )
            }
        }

        TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
    }

}