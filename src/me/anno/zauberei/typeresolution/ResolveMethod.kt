package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.ResolveField.findField
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.typeresolution.TypeResolution.applyTypeAlias
import me.anno.zauberei.typeresolution.TypeResolution.getSelfType
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveCall
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.ArrayType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

object ResolveMethod {

    // todo we should probably automatically detect underdefined methods (fields), and mark them as such,
    //  so we can the under-defined mechanism for methods (fields) that don't need it

    /**
     * finds a method, returns the method and any inserted type parameters
     * */
    fun findMethodInFile(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        var scope = scope ?: return null
        while (true) {
            val method = findMethodInScope(
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
    fun findMethodInHierarchy(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        if (scope == null || selfType !is ClassType) return null

        val method = findMethodInScope(
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
            findMethodInHierarchy(
                superType.clazz, name,
                returnType,
                mappedSelfType,
                mappedTypeParameters,
                valueParameters
            )
        }
    }

    fun findMethodInScope(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        scope ?: return null
        val scopeSelfType = getSelfType(scope)
        for (method in scope.methods) {
            if (method.name != name) continue
            if (method.typeParameters.isNotEmpty()) {
                println("Given $method on $selfType, with target $returnType, can we deduct any generics from that?")
            }
            val methodReturnType = if (returnType != null) {
                getMethodReturnType(scopeSelfType, method)
            } else method.returnType // no resolution invoked (fast-path)
            val generics = findGenericsForMatch(
                method.selfType, selfType,
                methodReturnType, returnType,
                method.typeParameters, typeParameters,
                method.valueParameters, valueParameters
            ) ?: continue
            return ResolvedMethod(method, generics)
        }
        return null
    }

    fun findConstructor(
        scope: Scope?, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        var scope = scope ?: return null
        while (true) {
            println("Searching constructors '$name' in ${scope.pathStr}, type: ${scope.scopeType}")
            if (scope.name == name) {
                val found = findConstructorImpl(scope, typeParameters, valueParameters)
                if (found != null) return found
            } else if (scope.scopeType == ScopeType.PACKAGE) {
                println("  ${scope.pathStr} is a package, looking inside")
                for (child in scope.children) {
                    if (child.name == name) {
                        val constructor = findConstructor(
                            child, name,
                            typeParameters, valueParameters
                        )
                        println("  constructor candidate for $name: $constructor")
                        if (constructor != null) {
                            return constructor
                        }
                    }
                }
            }

            val bestImport = scope.imports.firstOrNull { it.direct && it.name == name }
                ?: scope.imports.firstOrNull { !it.direct && it.name == name }
            if (bestImport != null) {
                return findConstructor(
                    bestImport.target, bestImport.target.name,
                    typeParameters, valueParameters
                )
            }

            scope = scope.parentIfSameFile ?: return null
        }
    }

    fun findConstructorImpl(
        scope: Scope?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        val scope = scope ?: return null
        val alias = scope.typeAlias
        if (alias != null) {
            val newType = applyTypeAlias(typeParameters, scope.typeParameters, alias)
            println("  mapped ${scope.pathStr}<$typeParameters> via $alias to ${newType.clazz.pathStr}<${newType.typeParameters}>")
            return findConstructorImpl(newType.clazz, newType.typeParameters, valueParameters)
        }

        for (constructor in scope.constructors) {
            println("  candidate constructor: $constructor")
            val generics = findGenericsForMatch(
                null, null,
                null, null,
                constructor.clazz.typeParameters, typeParameters,
                constructor.valueParameters, valueParameters
            ) ?: continue
            return ResolvedConstructor(constructor, generics)
        }
        return null
    }

    fun resolveCallType(
        context: ResolutionContext,
        expr: Expression,
        name: String,
        constructor: ResolvedCallable?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): Type {
        println("typeParams: $typeParameters")
        val targetType = context.targetType
        val selfType = context.selfType
        val method = constructor
            ?: findMethodInHierarchy(context.selfScope, name, targetType, selfType, typeParameters, valueParameters)
            ?: findMethodInFile(context.codeScope, name, targetType, selfType, typeParameters, valueParameters)
            ?: findMethodInFile(langScope, name, targetType, selfType, typeParameters, valueParameters)
        val field = findField(context.codeScope, selfType, name)
        val candidates =
            listOfNotNull(method?.getTypeFromCall(), field?.resolveCall()?.getTypeFromCall())
        if (candidates.isEmpty()) {
            val selfScope = context.selfScope
            val codeScope = context.codeScope
            println("self-scope methods[${selfScope?.pathStr}.'$name']: ${selfScope?.methods?.filter { it.name == name }}")
            println("code-scope methods[${codeScope.pathStr}.'$name']: ${codeScope.methods.filter { it.name == name }}")
            println("lang-scope methods[${langScope.pathStr}.'$name']: ${langScope.methods.filter { it.name == name }}")
            throw IllegalStateException(
                "Could not resolve method ${selfScope?.pathStr}.'$name'<$typeParameters>($valueParameters) " +
                        "in ${resolveOrigin(expr.origin)}, scopes: ${codeScope.pathStr}"
            )
        }
        if (candidates.size > 1) throw IllegalStateException("Cannot have both a method and a type with the same name '$name': $candidates")
        return candidates.first()
    }

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
            return null
        }

        println("checking types: $expectedTypeParameters vs $actualTypeParameters")
        println("   and  values: $expectedValueParameters vs $actualValueParameters")
        println("   and  selves: $expectedSelfType vs $actualSelfType")
        println("   and returns: $expectedReturnType vs $actualReturnType")

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

}