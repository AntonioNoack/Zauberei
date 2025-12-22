package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.impl.*

/**
 * Check if one type inherits from another, incl. generic checks.
 * */
object Inheritance {

    fun isSubTypeOf(
        expected: Parameter,
        actual: ValueParameter,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode
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
            insertMode
        )
    }

    fun isSubTypeOf(
        expectedType: Type,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode,
    ): Boolean {
        println("checking $actualType instanceOf $expectedType")
        val result = isSubTypeOfImpl(
            expectedType,
            actualType,
            expectedTypeParams,
            actualTypeParameters,
            insertMode,
        )
        println("got $result for $actualType instanceOf $expectedType")
        return result
    }

    private fun tryInsertGenericType(
        expectedType: GenericType,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode
    ): Boolean {

        // todo compare scope, too
        val typeParamIdx = expectedTypeParams.indexOfFirst { it.name == expectedType.name }
        if (typeParamIdx == -1) {
            if (insertMode != InsertMode.WEAK) {
                System.err.println("Missing generic parameter ${expectedType.name}, ignoring it")
            }// else can be safely ignored ;)
            return true
        }

        actualTypeParameters as FillInParameterList

        val expectedTypeParam = expectedTypeParams[typeParamIdx]
        if (!isSubTypeOf(
                // check bounds of expectedTypeParam
                expectedTypeParam.type,
                actualType,
                expectedTypeParams,
                actualTypeParameters,
                InsertMode.READ_ONLY,
            )
        ) return false

        actualTypeParameters.union(typeParamIdx, actualType, insertMode == InsertMode.STRONG)
        println("Found Type: [$typeParamIdx,'${expectedType.name}'] = ${actualTypeParameters[typeParamIdx]}")
        return true
    }

    private fun isSubTypeOfImpl(
        expectedType: Type,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode,
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
                    InsertMode.READ_ONLY,
                )
            }
            if (t0 || insertMode == InsertMode.READ_ONLY) return t0
            // then, try with inserting new types
            return actualType.types.all { allActual ->
                isSubTypeOf(
                    expectedType, allActual,
                    expectedTypeParams,
                    actualTypeParameters,
                    insertMode,
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
                    InsertMode.READ_ONLY
                )
            }
            if (t0 || insertMode == InsertMode.READ_ONLY) return t0
            // then, try with inserting new types
            return expectedType.types.any { anyExpected ->
                isSubTypeOf(
                    anyExpected, actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    insertMode
                )
            }
        }

        if (insertMode != InsertMode.READ_ONLY) {
            if (expectedType is GenericType) {
                return tryInsertGenericType(
                    expectedType, actualType,
                    expectedTypeParams, actualTypeParameters,
                    insertMode,
                )
            }

            if (actualType is GenericType) {
                return tryInsertGenericType(
                    // does this work with just swapping them???
                    actualType, expectedType,
                    expectedTypeParams, actualTypeParameters,
                    insertMode,
                )
            }
        }

        if ((expectedType == NullType) != (actualType == NullType)) {
            return false
        }

        println(
            "checkingEq: $expectedType vs $actualType " +
                    "-> ${expectedType == actualType}"
        )

        if (expectedType == actualType) return true
        if (actualType is ClassType && expectedType is ClassType) {
            // todo check generics
            if (expectedType.clazz == actualType.clazz) {
                val actualGenerics = actualType.typeParameters
                val expectedGenerics = expectedType.typeParameters
                val sufficient = actualType.classHasNoTypeParams()
                val actualSize = actualGenerics?.size ?: if (sufficient) 0 else -1
                val expectedSize = expectedGenerics?.size ?: if (sufficient) 0 else -1
                println("Class vs Class (${actualType.clazz.name}), $actualSize vs $expectedSize")

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
                                insertMode,
                            )
                        ) return false
                    }
                }

                return true
            }

            // println("classType of $expectedType: ${expectedType.clazz.scopeType}")
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
                        insertMode,
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
                // return type is one direction, actual type is the other...
                //  params are normal, return type is the other way around...
                //  -> this needs to be flipped
                actualType.returnType, expectedType.returnType,
                expectedTypeParams, actualTypeParameters,
                insertMode,
            ) && expectedType.parameters.indices.all { paramIndex ->
                isSubTypeOf(
                    expectedType.parameters[paramIndex].type,
                    actualType.parameters[paramIndex].type,
                    expectedTypeParams, actualTypeParameters,
                    insertMode,
                )
            }
        }

        if (insertMode == InsertMode.READ_ONLY) {
            if (expectedType is GenericType || actualType is GenericType) {
                return false
            }
        }

        TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters [$insertMode]")
    }

}