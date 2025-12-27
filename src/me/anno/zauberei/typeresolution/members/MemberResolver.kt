package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.typeresolution.FillInParameterList
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.InsertMode
import me.anno.zauberei.typeresolution.members.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.typeresolution.ValueParameter
import me.anno.zauberei.typeresolution.ValueParameterImpl
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

abstract class MemberResolver<Resource, Resolved : ResolvedCallable> {

    companion object {

        fun findGenericsForMatch(
            expectedSelfType: Type?,
            actualSelfType: Type?,

            expectedReturnType: Type?, /* null if nothing is expected */
            actualReturnType: Type?, // can help deducting types

            expectedTypeParameters: List<Parameter>,
            actualTypeParameters: List<Type>?,

            expectedValueParameters: List<Parameter>,
            actualValueParameters: List<ValueParameter>
        ): List<Type>? { // found generic values for a match

            // todo objects don't need actualSelfType, if properly in scope or imported...
            if ((expectedSelfType != null) != (actualSelfType != null)) {
                println("selfType mismatch: $expectedSelfType vs $actualSelfType")
                return null
            }

            println("checking types: $expectedTypeParameters vs $actualTypeParameters")
            println("  and   values: $expectedValueParameters vs $actualValueParameters")
            println("  and   selves: $expectedSelfType vs $actualSelfType")
            println("  and  returns: $expectedReturnType vs $actualReturnType")

            // first match everything by name
            // todo resolve default values... -> could be expanded earlier :)
            // todo resolve varargs...

            val isVararg = expectedValueParameters.lastOrNull()?.isVararg == true
            if (isVararg) {
                if (expectedValueParameters.size > actualValueParameters.size) {
                    println("param-size too low")
                    return null
                }
            } else {
                if (expectedValueParameters.size != actualValueParameters.size) {
                    println("param-size mismatch: expected ${expectedValueParameters.size}, but got ${actualValueParameters.size}")
                    return null
                }
            }

            if (actualTypeParameters != null && actualTypeParameters.size != expectedTypeParameters.size) {
                println("type-param-size mismatch: expected ${expectedTypeParameters.size}, but got ${actualTypeParameters.size}")
                return null
            }

            val sortedValueParameters = resolveNamedParameters(expectedValueParameters, actualValueParameters)
                ?: run {
                    println("param-name mismatch")
                    return null
                }

            val resolvedTypes = actualTypeParameters
                ?: FillInParameterList(expectedTypeParameters.size)

            val findGenericTypes = actualTypeParameters == null

            // println("Checking method-match, self-types: $expectedSelfType vs $actualSelfType")
            val matchesSelfType = expectedSelfType == null || isSubTypeOf(
                expectedSelfType, actualSelfType!!,
                expectedTypeParameters,
                resolvedTypes,
                if (findGenericTypes) InsertMode.STRONG else InsertMode.READ_ONLY
            )

            if (!matchesSelfType) {
                println("selfType-mismatch: $actualSelfType !is $expectedSelfType")
                return null
            }

            // todo this should only be executed sometimes...
            //  missing generic parameters can be temporarily inserted...
            // println("matchesReturnType($expectedReturnType vs $actualReturnType)")
            val matchesReturnType = expectedReturnType == null || actualReturnType == null ||
                    isSubTypeOf(
                        expectedReturnType,
                        actualReturnType,
                        expectedTypeParameters,
                        resolvedTypes,
                        if (findGenericTypes) InsertMode.WEAK else InsertMode.READ_ONLY,
                    )

            if (!matchesReturnType) {
                println("returnType-mismatch: $actualReturnType !is $expectedReturnType")
                return null
            }

            for (i in expectedValueParameters.indices) {
                val mvParam = expectedValueParameters[i]
                val vParam = if (mvParam.isVararg) {

                    val expectedParamArrayType = mvParam.type
                    check(expectedParamArrayType is ClassType)
                    check(expectedParamArrayType.clazz == ArrayType.clazz)
                    check(expectedParamArrayType.typeParameters?.size == 1)
                    // todo we might need to replace generics here...
                    val expectedParamEntryType = expectedParamArrayType.typeParameters[0]

                    // if star, use it as-is
                    val commonType = sortedValueParameters.subList(i, sortedValueParameters.size)
                        .map { it.getType(expectedParamEntryType) }
                        .reduce { a, b -> unionTypes(a, b) }
                    val joinedType = ClassType(ArrayType.clazz, listOf(commonType))
                    ValueParameterImpl(null, joinedType)
                } else {
                    sortedValueParameters[i]
                }
                if (!isSubTypeOf(
                        mvParam, vParam,
                        expectedTypeParameters,
                        resolvedTypes,
                        if (findGenericTypes) InsertMode.STRONG else InsertMode.READ_ONLY
                    )
                ) {
                    val type = vParam.getType(mvParam.type)
                    println("type mismatch: $type is not always a ${mvParam.type}")
                    return null
                }
            }

            val immutableList = if (resolvedTypes is FillInParameterList) resolvedTypes.types.asList() else resolvedTypes
            println("Found match: $immutableList")
            return immutableList as List<Type>
        }

        /**
         * Change the order of value parameters if needed.
         * execution order must remain unchanged!
         * */
        private fun resolveNamedParameters(
            methodValueParameters: List<Parameter>,
            valueParameters: List<ValueParameter>
        ): List<ValueParameter>? {
            return if (valueParameters.any { it.name != null }) {
                val list = arrayOfNulls<ValueParameter>(valueParameters.size)
                for (valueParam in valueParameters) {
                    val name = valueParam.name ?: continue
                    val index = methodValueParameters.indexOfFirst { it.name == name }
                    if (index < 0) return null
                    check(list[index] == null)
                    list[index] = valueParam
                }
                var index = 0
                for (valueParam in valueParameters) {
                    if (valueParam.name != null) continue
                    while (list[index] != null) index++
                    list[index] = valueParam
                }
                check(list.none { it == null })
                list.toList() as List<ValueParameter>
            } else valueParameters
        }
    }

    // todo we should probably automatically detect underdefined methods (fields), and mark them as such,
    //  so we can the under-defined mechanism for methods (fields) that don't need it

    /**
     * finds a method, returns the method and any inserted type parameters
     * */
    fun findMemberInFile(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): Resolved? {
        var scope = scope ?: return null
        while (true) {
            val method = findMemberInScope(
                scope, name,
                returnType, selfType,
                typeParameters, valueParameters
            )
            if (method != null) return method

            scope = scope.parentIfSameFile ?: return null
        }
    }

    /**
     * finds a method, returns the method and any inserted type parameters
     * todo check whether this works... the first call should be checked whether expectedSelfType & scope are the same
     * */
    fun findMemberInHierarchy(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): Resolved? {
        if (scope == null || selfType !is ClassType) return null

        val method = findMemberInScope(
            scope, name,
            returnType, selfType,
            typeParameters, valueParameters
        )
        if (method != null) return method

        return scope.superCalls.firstNotNullOfOrNull { call ->
            val superType = call.type
            val genericNames = scope.typeParameters
            val genericValues = call.type.typeParameters ?: emptyList()
            val mappedSelfType = resolveGenerics(selfType, genericNames, genericValues) as ClassType
            val mappedTypeParameters = typeParameters?.map {
                resolveGenerics(it, genericNames, genericValues)
            }
            check(superType.clazz != selfType.clazz)
            findMemberInHierarchy(
                superType.clazz, name,
                returnType,
                mappedSelfType,
                mappedTypeParameters,
                valueParameters
            )
        }
    }

    abstract fun findMemberInScope(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): Resolved?

}