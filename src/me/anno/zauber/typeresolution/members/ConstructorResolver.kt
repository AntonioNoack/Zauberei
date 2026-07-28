package me.anno.zauber.typeresolution.members

import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ParameterList.Companion.resolveGenerics
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.typeToScope
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType

object ConstructorResolver : MemberResolver<Constructor, ResolvedConstructor>() {

    private val LOGGER = LogManager.getLogger(ConstructorResolver::class)

    override fun findMemberInScope(
        scope: Scope?, origin: Long, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext
    ): ResolvedConstructor? {
        LOGGER.info("Checking scope '$scope' for constructor '$name'")
        scope ?: return null
        if (scope.name == name) {
            val constructor = findMemberInScopeImpl(scope, name, typeParameters, valueParameters, context, origin)
            if (constructor != null) return constructor
        }
        LOGGER.info("  children: ${scope.children.map { it.name }}")
        for (child in scope.children) {
            if (child.name == name/* && child.scopeType?.isClassType() == true*/) {
                LOGGER.info("Found constructor-name pre-match: $child")
                val constructor = findMemberInScopeImpl(
                    child[ScopeInitType.AFTER_OVERRIDES], name,
                    typeParameters, valueParameters, context, origin
                )
                if (constructor != null) return constructor
            }
        }
        return null
    }

    fun findMemberInScopeImpl(
        scope: Scope, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext,
        origin: Long
    ): ResolvedConstructor? {

        check(scope.name == name) {
            "Expected $name, but got '${scope.pathStr}'"
        }

        if (scope.scopeType == ScopeType.TYPE_ALIAS) {
            return getByTypeAlias(scope, typeParameters, valueParameters, context, origin)
        }

        val children = scope.children
        var bestMatch: ResolvedConstructor? = null
        for (i in children.indices) {
            val constructor = children[i][ScopeInitType.AFTER_OVERRIDES].selfAsConstructor ?: continue
            // if (method.name != name) continue
            if (constructor.typeParameters.isNotEmpty()) {
                LOGGER.info("Given $constructor in $context, can we deduct any generics from that?")
            }
            val returnType = context.targetType
            val match = FindMemberMatch.findMemberMatch(
                constructor, constructor.selfType, returnType,
                null,
                typeParameters, valueParameters,
                context.withCodeScope(scope), origin
            ) as? ResolvedConstructor
            LOGGER.info("Match($constructor): $match")
            if (match != null && (bestMatch == null || match.matchScore < bestMatch.matchScore)) {
                bestMatch = match
            }
        }
        return bestMatch
    }

    private fun getByTypeAlias(
        scope: Scope,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext,
        origin: Long
    ): ResolvedConstructor? {

        // this can still happen during type-resolution,
        //  because we don't know yet whether a method call is a constructor with 100% accuracy

        val newType = scope.selfAsTypeAlias!!.resolvedName
        val newTypeParams = (newType as? ClassType)?.typeParameters

        val typeParameters0 = typeParameters
            ?.toParameterList(scope.typeParameters)

        val selfType = context.selfType
        val newType2 = typeParameters0.resolveGenerics(selfType, newType)

        val typeParameters2 = typeParameters?.map { typeParam ->
            typeParameters0.resolveGenerics(selfType, typeParam)
        } ?: newTypeParams

        val scope = typeToScope(newType2)!!
        return findMemberInScopeImpl(
            scope, scope.name,
            typeParameters2, valueParameters,
            context, origin
        )
    }

    fun List<Type>.toParameterList(generics: List<Parameter>): ParameterList {
        if (isEmpty()) return emptyParameterList()
        return ParameterList(generics, this)
    }
}