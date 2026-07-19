package me.anno.zauber.typeresolution.members

import me.anno.utils.PairArrayList
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.member.Member
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.CallWithNames.resolveNamedParameters
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.typeresolution.InsertMode
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType

abstract class MemberResolver<Resource : Member, Resolved : ResolvedMember<Resource>> {

    companion object {
        private val LOGGER = LogManager.getLogger(MemberResolver::class)

        fun findGenericsForMatch(
            expectedSelfType: Type?,
            actualSelfType: Type?,

            expectedReturnType: Type?, /* null if nothing is expected */
            actualReturnType: Type?, // can help deducting types

            expectedTypeParameters: List<Parameter>,
            actualTypeParameters: ParameterList?,

            expectedValueParameters: List<Parameter>,
            actualValueParameters: List<ValueParameter>,

            matchScore: MatchScore?
        ): ParameterList? { // found generic values for a match

            // todo objects don't need actualSelfType, if properly in scope or imported...
            if ((expectedSelfType != null) != (actualSelfType != null)) {
                if (LOGGER.isInfoEnabled) {
                    LOGGER.info("SelfType mismatch: $expectedSelfType vs $actualSelfType")
                }
                return null
            }

            if (LOGGER.isInfoEnabled) {
                LOGGER.info("Checking types: $expectedTypeParameters vs $actualTypeParameters")
                LOGGER.info("  and   values: $expectedValueParameters vs $actualValueParameters")
                LOGGER.info("  and   selves: $expectedSelfType vs $actualSelfType")
                LOGGER.info("  and  returns: $expectedReturnType vs $actualReturnType")
            }

            // first match everything by name
            // todo resolve default values... -> could be expanded earlier :)
            // todo resolve varargs...

            val isVararg = expectedValueParameters.lastOrNull()?.isVararg == true
            if (isVararg) {
                if (expectedValueParameters.size > actualValueParameters.size + 1) {
                    if (LOGGER.isInfoEnabled) LOGGER.info("  param-size too low, ${expectedValueParameters.size} vs ${actualValueParameters.size}")
                    return null
                }
            } else {
                if (expectedValueParameters.size != actualValueParameters.size) {
                    if (LOGGER.isInfoEnabled) LOGGER.info("  param-size mismatch: expected ${expectedValueParameters.size}, but got ${actualValueParameters.size}")
                    return null
                }
            }

            if (actualTypeParameters != null && actualTypeParameters.size != expectedTypeParameters.size) {
                LOGGER.info("  type-param-size mismatch: expected ${expectedTypeParameters.size}, but got ${actualTypeParameters.size}")
                return null
            }

            val sortedValueParameters = resolveNamedParameters(expectedValueParameters, actualValueParameters)
                ?: run {
                    if (LOGGER.isInfoEnabled) LOGGER.info("  param-name mismatch")
                    return null
                }

            if (LOGGER.isInfoEnabled) LOGGER.info("Sorted value parameters: $sortedValueParameters")

            check(sortedValueParameters.size == expectedValueParameters.size) {
                "Incorrectly sorted value parameters, expected ${expectedValueParameters.size} but got ${sortedValueParameters.size}"
            }

            val actualTypeParametersI = actualTypeParameters?.readonly()
                ?: ParameterList(expectedTypeParameters)

            if (LOGGER.isInfoEnabled) LOGGER.info("Actual type parameters: $actualTypeParametersI from $actualTypeParameters, expected: $expectedTypeParameters")

            // LOGGER.info("Checking method-match, self-types: $expectedSelfType vs $actualSelfType")
            if (expectedSelfType != null &&
                actualSelfType != null &&
                actualSelfType != expectedSelfType
            ) {
                LOGGER.info("Start checking self-type")
                val matchesSelfType = isSubTypeOf(
                    expectedSelfType, actualSelfType,
                    expectedTypeParameters,
                    actualTypeParametersI,
                    InsertMode.STRONG,
                    matchScore?.at(0)
                )
                if (LOGGER.isInfoEnabled) LOGGER.info("Done checking self-type")

                if (!matchesSelfType) {
                    if (LOGGER.isInfoEnabled) LOGGER.info("  selfType-mismatch: $actualSelfType !is $expectedSelfType")
                    return null
                }
            }

            // todo this should only be executed sometimes...
            //  missing generic parameters can be temporarily inserted...
            // LOGGER.info("matchesReturnType($expectedReturnType vs $actualReturnType)")
            if (expectedReturnType != null && actualReturnType != null) {
                if (LOGGER.isInfoEnabled) LOGGER.info("Start checking return-type")
                /*val matchesReturnType =*/ isSubTypeOf(
                    expectedReturnType,
                    actualReturnType,
                    expectedTypeParameters,
                    actualTypeParametersI,
                    InsertMode.WEAK,
                    matchScore?.at(expectedValueParameters.size + 1)
                )
                if (LOGGER.isInfoEnabled) LOGGER.info("Done checking return-type")

                /*if (!matchesReturnType) {
                    LOGGER.info("  returnType-mismatch: $actualReturnType !is $expectedReturnType")
                    return null
                }*/
            } else {
                if (LOGGER.isInfoEnabled) LOGGER.info(
                    "Skipped return-type matching: " +
                            "$expectedReturnType != null && " +
                            "$actualReturnType != null"
                )
            }

            for (i in expectedValueParameters.indices) {
                val expectedValueParameter = expectedValueParameters[i]
                val actualValueParameter = sortedValueParameters[i]
                if (LOGGER.isInfoEnabled) {
                    LOGGER.info("Start checking argument[$i]: $expectedValueParameter vs $actualValueParameter")
                    LOGGER.info("                       [$i]: $expectedTypeParameters vs $actualTypeParametersI")
                }
                if (!isSubTypeOf(
                        actualSelfType,
                        expectedValueParameter, actualValueParameter,
                        expectedTypeParameters, actualTypeParametersI,
                        InsertMode.STRONG, matchScore?.at(i + 1)
                    )
                ) {
                    val type = actualValueParameter.getType(expectedValueParameter.type)
                    if (LOGGER.isInfoEnabled) {
                        LOGGER.info("  type mismatch: $type is not always a ${expectedValueParameter.type}")
                        LOGGER.info("End checking arguments")
                        LOGGER.info("End checking argument[$i]")
                    }
                    return null
                }
                if (LOGGER.isInfoEnabled) LOGGER.info("End checking argument[$i]")
            }

            if (LOGGER.isInfoEnabled) LOGGER.info("Found match: $actualTypeParametersI")
            return actualTypeParametersI.readonly()
        }
    }

    /**
     * finds a method, returns the method and any inserted type parameters
     * */
    fun findMemberInFile(
        scope: Scope?, origin: Long, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext,
    ): Resolved? {
        var scope = scope ?: return null
        while (true) {
            val method = findMemberInScope(
                scope, origin, name,
                returnType, selfType,
                typeParameters, valueParameters,
                context
            )
            if (method != null) return method

            scope = scope.parentIfSameFile ?: return null
        }
    }

    fun findMemberInScope(
        scope: Scope?, origin: Long, name: String,

        hintedReturnType: Type?, // sometimes, we know what to expect from the return type
        explicitSelfType: Type?, // constructors of inner classes have this defined, others not

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext
    ): Resolved? = findMemberInScope(
        scope, origin, name,
        typeParameters, valueParameters,
        context.withSelfType(explicitSelfType)
            .withTargetType(hintedReturnType),
    )

    abstract fun findMemberInScope(
        scope: Scope?, origin: Long,
        name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext
    ): Resolved?

    private fun getOuterClassDepth(scope: Scope?): Int {
        // if there is a method in the tree, we can use its class, too
        //  -> find the lowest method
        var scopeI = scope ?: return 0
        var lowestMethodScope: Scope? = null
        while (true) {
            val scopeType = scopeI.scopeType
            if (scopeType == ScopeType.METHOD) {
                lowestMethodScope = scopeI
            }
            scopeI = scopeI.parentIfSameFile ?: break
        }

        var scopeJ = lowestMethodScope ?: scope
        while (true) {
            if (scopeJ.isClassLike() && scopeJ.scopeType != ScopeType.INNER_CLASS) {
                // inner class continues one down
                return scopeJ.path.size
            }

            scopeJ = scopeJ.parentIfSameFile
                ?: return scopeJ.path.size
        }
    }

    fun <R : Any> resolveInCodeScope(
        context: ResolutionContext, codeScope: Scope,
        callback: (scope: Scope, selfType: Type) -> R?
    ): R? {

        val contextSelfScope = context.selfScope?.get(ScopeInitType.AFTER_OVERRIDES)
        val contextSelfType = context.selfType?.specialize(context)

        val print = LOGGER.isInfoEnabled
        if (print) LOGGER.info("ResolveInCodeScope($codeScope, ${contextSelfScope}, ${contextSelfType})")

        if (contextSelfScope != null && contextSelfType != null) {

            if (print) LOGGER.info(
                "Checking[0] $contextSelfScope with ${contextSelfType}, " +
                        "fields: ${contextSelfScope.fields.map { it.name }}, methods: ${contextSelfScope.methods.map { it.name }}"
            )
            val result = callback(contextSelfScope, contextSelfType)
            if (result != null) return result

            // doesn't need specialization, because only one instance can exist
            val selfCompanion = contextSelfScope.companionObject
            if (selfCompanion != null) {
                if (print) LOGGER.info("Checking[1] $selfCompanion")
                val result = callback(selfCompanion, selfCompanion.typeWithArgs)
                if (result != null) return result
            } else {
                if (print) LOGGER.info("$contextSelfScope has no companion")
            }
        } else {
            // handle all self-invocations
            // todo we also somehow need to set 'this' into the resolved method...
            for (explicitThis in context.extensionThis) {
                val explicitThisScope = explicitThis.thisTypeToScope ?: langScope // is langScope correct as a fallback?
                if (print) LOGGER.info("Checking[ext] $explicitThisScope with ${explicitThis.thisType}")
                val result = callback(explicitThisScope, explicitThis.thisType)
                if (result != null) return result
            }
        }

        val scopes = PairArrayList<Scope, Type>()
        if (contextSelfScope != null && contextSelfType != null) {
            scopes.add(contextSelfScope, contextSelfType)
        }

        listScopeTypeCandidates(context, codeScope, scopes)

        if (print) LOGGER.info("Scopes/selfTypes: $scopes")

        val selfType0 = scopes.firstBOrNull()
        val selfTypeZ = contextSelfType ?: selfType0

        // selfType goes over all scopes below it...
        for (scopeIndex in scopes.indices) {
            val scope = scopes.getA(scopeIndex) // should be unique by itself
            var lastType: Type? = null // to avoid duplicate checking

            for (typeIndex in 0..scopeIndex) {
                val type = scopes.getB(typeIndex)
                if (type == lastType) continue // already done

                if (print) LOGGER.info("Checking[i] $scope with $type")
                val result = callback(scope, type)
                if (result != null) return result
                lastType = type
            }
        }

        // we're missing the self-case... process it now
        if ((contextSelfScope == null || contextSelfType == null) && selfTypeZ is ClassType) {
            if (print) LOGGER.info("Checking[y] ${selfTypeZ.clazz} with $selfTypeZ")
            val result = callback(selfTypeZ.clazz, selfTypeZ)
            if (result != null) return result
        } else if (print) {
            LOGGER.info("Not-Checking[y]: ($contextSelfScope == null && $contextSelfType == null) || $selfTypeZ !is ClassType")
        }

        return null
    }

    private fun listScopeTypeCandidates(
        context: ResolutionContext, codeScope: Scope,
        result: PairArrayList<Scope, Type>
    ) {
        listScopeTypeCandidates(context, codeScope) { scope, selfType ->
            scope[ScopeInitType.AFTER_OVERRIDES]
            result.add(scope, selfType)

            // if scope has a companion object, list that, too
            // unless we're already inside it...
            val companionObject = scope.companionObject
            if (companionObject != null && companionObject != result.getAOrNull(result.size - 2)) {
                result.add(companionObject, companionObject.typeWithArgs)
            }
        }
    }

    private fun listScopeTypeCandidates(
        context: ResolutionContext, codeScope: Scope,
        callback: (scope: Scope, selfType: Type) -> Unit
    ) {
        var scope = codeScope
        val outerClassDepth = getOuterClassDepth(scope)
        // println("Outer class depth: $outerClassDepth")
        while (true) {
            if (isScopeAvailable(scope, outerClassDepth)) {
                val selfType = resolveTypeFromScoping(scope, context)?.specialize(context)
                if (selfType != null) callback(scope, selfType)

                val selfType0 = scope.typeWithArgs
                if (selfType0 != selfType) {
                    callback(scope, selfType0)
                }
            }
            scope = scope.parentIfSameFile ?: break
        }

        callback(langScope, langScope.typeWithArgs)
        callback(root, root.typeWithArgs)
    }

    private fun resolveTypeFromScoping(candidateScope: Scope, context: ResolutionContext): Type? {
        var candidateScope: Scope = candidateScope
        while (candidateScope.isInsideExpression()) {
            if (candidateScope.scopeType == ScopeType.LAMBDA) {
                val candidateLambda = candidateScope.selfAsLambda
                    ?: error("$candidateScope is marked as a lambda, so it must set selfAsLambda")
                // candidateLambda.resolveCallable()
                // TODO("We're a lambda @$candidateScope, so find our selfType, $candidateLambda")
            }
            candidateScope = candidateScope.parentIfSameFile ?: break
        }

        if (candidateScope == (context.selfType?.resolvedName as? ClassType)?.clazz) {
            // println("Found context.selfType: $candidateScope")
            return context.selfType
        }

        // if candidateScope is method & has self type, use that instead
        val selfAsMethod = candidateScope.selfAsMethod
        if (selfAsMethod != null) {
            val selfType = selfAsMethod.selfType
            if (selfType != null) {
                // println("Found method.selfType: $selfType")
                return selfType
            }
        }

        if (!candidateScope.isClassLike()) return null

        return candidateScope.typeWithArgs
    }

    private fun isScopeAvailable(scope: Scope, originalSelfScope: Int): Boolean {
        return when (scope.scopeType) {
            // todo we need to filter by visibility
            ScopeType.INLINE_CLASS, ScopeType.PACKAGE, ScopeType.OBJECT, ScopeType.ENUM_CLASS,
            ScopeType.METHOD, ScopeType.METHOD_BODY, ScopeType.WHEN_CASES, ScopeType.WHEN_ELSE,
            ScopeType.LAMBDA -> true // only one instance
            ScopeType.INTERFACE, ScopeType.NORMAL_CLASS ->
                scope.path.size >= originalSelfScope
            else -> true // idk
        }
    }

}