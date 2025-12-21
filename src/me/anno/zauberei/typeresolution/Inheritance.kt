package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.impl.*
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

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
        val expectedType = resolveGenerics(
            expected.type,
            expectedTypeParams.filterIndexed { index, _ -> actualTypeParameters[index] != null },
            actualTypeParameters.filterNotNull()
        )
        if (expected.type != expectedType) {
            println("Resolved ${expected.type} to $expectedType for isSubTypeOf")
        }
        return isSubTypeOf(
            expectedType,
            actual.getType(expectedType),
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

    private fun checkGenericInsert(
        expectedType: GenericType,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        findGenericTypes: Boolean
    ): Boolean {
        if (findGenericTypes) {
            val typeParamIdx = expectedTypeParams.indexOfFirst { it.name == expectedType.name }
            actualTypeParameters as MutableList<Type?>

            val oldActualTypeParam = actualTypeParameters[typeParamIdx]
            val expectedTypeParam = expectedTypeParams[typeParamIdx]
            if (!isSubTypeOf( // check bounds of expectedTypeParam
                    expectedTypeParam.type,
                    actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    insertTypes = false,
                    findGenericTypes = false
                )
            ) return false

            val newTypeParam = if (oldActualTypeParam != null) {
                unionTypes(oldActualTypeParam, actualType)
            } else {
                actualType
            }

            actualTypeParameters[typeParamIdx] = newTypeParam
            println("Found Type: [$typeParamIdx,'${expectedType.name}'] = ${actualTypeParameters[typeParamIdx]}")
            return true

        } else TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters")
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

        if (expectedType is GenericType) {
            return checkGenericInsert(
                expectedType, actualType,
                expectedTypeParams, actualTypeParameters,
                findGenericTypes
            )
        }

        if (actualType is GenericType) {
            return checkGenericInsert(
                // does this work with just swapping them???
                actualType, expectedType,
                expectedTypeParams, actualTypeParameters,
                findGenericTypes
            )
        }

        if ((expectedType == NullType) != (actualType == NullType)) {
            return false
        }

        println(
            "checking-1: $expectedType vs $actualType " +
                    "-> ${expectedType == actualType}"
        )

        if (expectedType == actualType) return true
        if (actualType is ClassType && expectedType is ClassType) {
            // todo check generics
            if (expectedType.clazz == actualType.clazz) {
                val actualGenerics = actualType.typeParameters
                val expectedGenerics = expectedType.typeParameters
                val actualSize = actualGenerics?.size ?: if (actualType.hasSufficientTypeParameters()) 0 else -1
                val expectedSize = expectedGenerics?.size ?: if (expectedType.hasSufficientTypeParameters()) 0 else -1
                println("Class vs Class, $actualSize vs $expectedSize")

                if (actualSize != expectedSize) {
                    println("Mismatch in generic count :(")
                    return false
                }

                // todo in/out now matters for the direction of the isSubTypeOf...
                if (actualGenerics != null && expectedGenerics != null) {
                    for (i in actualGenerics.indices) {
                        val expectedType = expectedGenerics[i]
                        val actualType = actualGenerics[i]
                        if (!isSubTypeOf(
                                expectedType, actualType,
                                expectedTypeParams, actualTypeParameters,
                                insertTypes, findGenericTypes
                            )
                        ) return false
                    }
                }

                return true
            }

            println("classType of $expectedType: ${expectedType.clazz.scopeType}")
            return when (expectedType.clazz.scopeType) {
                ScopeType.INTERFACE -> {
                    TODO("check super interfaces of $actualType for $expectedType")
                }
                else -> {
                    val isAnyClass = actualType.clazz == AnyType.clazz
                    if (isAnyClass) return false

                    // check super class
                    // todo if super type has generics, we need to inject them into the super type
                    val superType = actualType.clazz.superCalls.firstOrNull { it.valueParams != null }?.type ?: AnyType
                    println("super($actualType): $superType")
                    isSubTypeOf(
                        expectedType,
                        superType,
                        expectedTypeParams,
                        actualTypeParameters,
                        insertTypes,
                        findGenericTypes
                    )
                }
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