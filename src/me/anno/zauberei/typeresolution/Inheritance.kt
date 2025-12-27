package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.SuperCall
import me.anno.zauberei.typeresolution.members.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.Scope
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
        println("  with generics $expectedTypeParams")
        val result = isSubTypeOfImpl(
            expectedType,
            actualType,
            expectedTypeParams,
            actualTypeParameters,
            insertMode,
        )
        println("  got $result for $actualType instanceOf $expectedType")
        return result
    }

    private fun tryInsertGenericType(
        expectedType: GenericType,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode
    ): Boolean {
        check(insertMode != InsertMode.READ_ONLY)

        val typeParamIdx = expectedTypeParams.indexOfFirst {
            it.name == expectedType.name &&
                    it.scope == expectedType.scope
        }

        if (typeParamIdx == -1) {
            /*val generallyExpectedType = expectedType.superBounds
            println("Missing $expectedType for $actualType, falling back to $generallyExpectedType")
            return isSubTypeOf(
                generallyExpectedType,
                actualType,
                expectedTypeParams,
                actualTypeParameters,
                insertMode
            )*/
            if (insertMode != InsertMode.WEAK) {
                System.err.println("Missing generic parameter ${expectedType.name}, ignoring it")
            }// else can be safely ignored ;)
            return true
            // System.err.println("Missing generic parameter ${expectedType.scope.pathStr}.${expectedType.name}, ignoring it")
            // System.err.println("Available generic parameters: ${expectedTypeParams.map { "${it.scope.pathStr}.${it.name}" }}")
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

        val success = actualTypeParameters.union(typeParamIdx, actualType, insertMode == InsertMode.STRONG)
        println("Found Type[$success for $actualType @$insertMode]: [$typeParamIdx,${expectedType.scope.pathStr}.${expectedType.name}] = ${actualTypeParameters[typeParamIdx]}")
        return success
    }

    private fun isSubTypeOfImpl(
        expectedType: Type,
        actualType: Type,
        expectedTypeParams: List<Parameter>,
        actualTypeParameters: List<Type?>,
        insertMode: InsertMode,
    ): Boolean {

        if (expectedType == actualType) return true

        if (expectedType is NotType) {
            return !isSubTypeOf(
                expectedType.not(), actualType,
                expectedTypeParams, actualTypeParameters, insertMode
            )
        }

        if (actualType is NotType) {
            return !isSubTypeOf(
                expectedType, actualType.not(),
                expectedTypeParams, actualTypeParameters, insertMode
            )
        }

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

        if (expectedType is AndType) {
            // first try without inserting types
            val t0 = expectedType.types.all { anyExpected ->
                isSubTypeOf(
                    anyExpected, actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    InsertMode.READ_ONLY
                )
            }
            if (t0 || insertMode == InsertMode.READ_ONLY) return t0
            // then, try with inserting new types
            return expectedType.types.all { anyExpected ->
                isSubTypeOf(
                    anyExpected, actualType,
                    expectedTypeParams,
                    actualTypeParameters,
                    insertMode
                )
            }
        }

        if (actualType is AndType) {
            // everything must fit
            // first try without inserting types
            val t0 = actualType.types.any { allActual ->
                isSubTypeOf(
                    expectedType, allActual,
                    expectedTypeParams,
                    actualTypeParameters,
                    InsertMode.READ_ONLY,
                )
            }
            if (t0 || insertMode == InsertMode.READ_ONLY) return t0
            // then, try with inserting new types
            return actualType.types.any { allActual ->
                isSubTypeOf(
                    expectedType, allActual,
                    expectedTypeParams,
                    actualTypeParameters,
                    insertMode,
                )
            }
        }

        if (insertMode != InsertMode.READ_ONLY) {
            if (actualType is GenericType) {
                return tryInsertGenericType(
                    // does this work with just swapping them???
                    actualType, expectedType,
                    expectedTypeParams, actualTypeParameters,
                    insertMode,
                )
            }

            if (expectedType is GenericType) {
                return tryInsertGenericType(
                    expectedType, actualType,
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
                if (expectedGenerics == null) {
                    println("Nothing is expected for generics, matching")
                    return true
                }

                if (actualGenerics == null /*&&
                    expectedGenerics.all {
                        it is GenericType &&
                                expectedTypeParams.none { p -> p.scope == it.scope && p.name == it.name }
                    }*/
                ) {
                    println("Actual generics unknown -> continue with true (?)")
                    return true
                }

                val sufficient = actualType.classHasNoTypeParams()
                val actualSize = actualGenerics?.size ?: if (sufficient) 0 else -1
                val expectedSize = expectedGenerics.size
                println("Class vs Class (${actualType.clazz.name}), $actualSize vs $expectedSize, $insertMode")

                if (actualSize != expectedSize) {
                    println("Mismatch in generic count :(")
                    return false
                }

                // todo in/out now matters for the direction of the isSubTypeOf...
                if (actualGenerics != null) {
                    for (i in actualGenerics.indices) {
                        // these may be null, if so, just accept them
                        val expectedType = expectedGenerics[i] ?: return false//?: NullableAnyType
                        val actualType = actualGenerics[i] ?: return false//?: NullableAnyType
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

            // check super class
            // todo if super type has generics, we need to inject them into the super type
            return getSuperCalls(actualType.clazz).any { superCall ->
                val superType = superCall.type
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
                val expectedType = if (expectedType is GenericType) expectedType.superBounds else expectedType
                val actualType = if (actualType is GenericType) actualType.superBounds else expectedType
                println("Using superBounds...")
                return isSubTypeOf(
                    expectedType, actualType,
                    expectedTypeParams, actualTypeParameters,
                    insertMode,
                )
            }
        }

        TODO("Is $actualType a $expectedType?, $expectedTypeParams, $actualTypeParameters [$insertMode]")
    }

    fun getSuperCalls(scope: Scope): List<SuperCall> {
        if (scope == AnyType.clazz) return emptyList()
        if (scope.superCalls.isEmpty()) return listOf(superCallAny)
        return scope.superCalls
    }

    private val superCallAny = SuperCall(AnyType, emptyList(), null)

}