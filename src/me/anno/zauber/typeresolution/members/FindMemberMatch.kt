package me.anno.zauber.typeresolution.members

import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Member
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.typeresolution.members.MemberResolver.Companion.findGenericsForMatch
import me.anno.zauber.typeresolution.members.MergeTypeParams.collectSpecialization
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType

object FindMemberMatch {

    private val LOGGER = LogManager.getLogger(FindMemberMatch::class)

    private fun findOwnerClass(scope: Scope): Scope {
        var scope = scope
        while (true) {
            if (scope.isClassLike()) return scope
            scope = scope.parent!!
        }
    }

    fun <M : Member> findMemberMatch(
        member: M,
        memberReturnType: Type?, // refined, if we have context hints

        hintedReturnType: Type?, // sometimes, we know what to expect from the return type
        explicitSelfType: Type?, // e.g. for a.x, we get typeOf(a), else null

        explicitTypeParameters: List<Type>?,
        explicitValueParameters: List<ValueParameter>,
        context: ResolutionContext, origin: Long
    ): ResolvedMember<*>? {

        var explicitSelfType = explicitSelfType
        if (explicitSelfType == null && member is Constructor) {
            // hack: selfType is typically not set for constructors, only for inner classes
            explicitSelfType = member.ownerScope.typeWithoutArgs // <- without, so we don't force it
        }

        if (explicitSelfType == null &&
            member is Field &&
            member.isObjectInstance() &&
            member.ownerScope.parent == root
        ) {
            // for package fields
            explicitSelfType = member.ownerScope.typeWithArgs
        }

        if (member is Field && member.isObjectInstance() &&
            member.ownerScope.parent == (explicitSelfType as? ClassType)?.clazz
        ) {
            // for fields inside objects, where selfType is given,
            //   but points to the object containing the object
            explicitSelfType = member.ownerScope.typeWithArgs
        }

        val avp = explicitValueParameters.size
        val mvp = member.valueParameters.size
        if (if (member.hasExpandingParameter) avp + 1 < mvp else avp < mvp) {
            LOGGER.info("Rejecting $member, not enough value parameters")
            return null
        }
        if (avp > mvp && !member.hasExpandingParameter) {
            LOGGER.info("Rejecting $member, too many value parameters")
            return null
        }

        val targetTypeParams = if (member is Constructor) member.ownerScope.typeParameters else member.typeParameters
        if (explicitTypeParameters != null && explicitTypeParameters.size != targetTypeParams.size) {
            LOGGER.info("Rejecting $member, mismatch in number of type parameters")
            return null
        }

        val matchScore = MatchScore(member.valueParameters.size + 2)
        val memberSelfType = member.selfType?.resolvedName
            ?: findOwnerClass(member.scope).typeWithArgs

        if (member.ownerScope.isMethodLike() || member.ownerScope.isInsideExpression()) {
            // hack: we assume we have access, because the scope was somehow discovered
            explicitSelfType = memberSelfType
        }

        if (explicitSelfType == null) {
            LOGGER.info("Rejecting $member, selfType is missing")
            return null
        }

        val expectedTypeParams = Specialization.collectGenerics(member.scope)
        val actualTypeParams = collectSpecialization(
            expectedTypeParams, explicitSelfType,
            if (explicitTypeParameters != null) {
                ParameterList(targetTypeParams, explicitTypeParameters)
            } else null,
            context.specialization, origin
        )

        if (LOGGER.isInfoEnabled) LOGGER.info("Resolving generics for $member")
        // println("Resolving generics for $method")
        val generics = findGenericsForMatch(
            memberSelfType, explicitSelfType,
            memberReturnType, hintedReturnType,
            expectedTypeParams, actualTypeParams,
            member.valueParameters, explicitValueParameters, matchScore,
            context
        )
        if (generics == null) {
            LOGGER.info("Rejecting $member, generic-mismatch")
            return null
        }

        val selfType1 = explicitSelfType ?: memberSelfType
        val specialization = Specialization(member.memberScope, generics)
        val codeScope = context.codeScope
        val context = ResolutionContext(codeScope, selfType1, specialization, false, hintedReturnType)
        return when (member) {
            is Method -> ResolvedMethod(member, context, codeScope, matchScore)
            is Field -> ResolvedField(member, context, codeScope, matchScore)
            is Constructor -> ResolvedConstructor(member, context, codeScope, matchScore)
            else -> error("Unknown member type: $member")
        }
    }

}