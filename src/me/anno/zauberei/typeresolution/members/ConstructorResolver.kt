package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.ValueParameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

object ConstructorResolver : MemberResolver<Constructor, ResolvedConstructor>() {

    override fun findMemberInScope(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedConstructor? {
        println("Checking $scope for constructor $name")
        scope ?: return null
        if (scope.name == name) {
            val constructor = findMemberInScopeImpl(scope, name, returnType, selfType, typeParameters, valueParameters)
            if (constructor != null) return constructor
        }
        println("  children: ${scope.children.map { it.name }}")
        for (child in scope.children) {
            if (child.name == name/* && child.scopeType?.isClassType() == true*/) {
                val constructor =
                    findMemberInScopeImpl(child, name, returnType, selfType, typeParameters, valueParameters)
                if (constructor != null) return constructor
            }
        }
        return null
    }

    private fun findMemberInScopeImpl(
        scope: Scope, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): ResolvedConstructor? {
        println("Checking $scope for constructors")
        check(scope.name == name)
        for (member in scope.constructors) {
            // if (method.name != name) continue
            if (member.typeParameters.isNotEmpty()) {
                println("Given $member on $selfType, with target $returnType, can we deduct any generics from that?")
            }
            val match = findMemberMatch(
                member, member.selfType,
                returnType,
                typeParameters, valueParameters,
            )
            println("Match($member): $match")
            if (match != null) return match
        }
        return null
    }

    private fun findMemberMatch(
        constructor: Constructor,
        memberReturnType: Type?,

        returnType: Type?, // sometimes, we know what to expect from the return type

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): ResolvedConstructor? {
        val generics = findGenericsForMatch(
            null, null,
            memberReturnType, returnType,
            constructor.selfType.clazz.typeParameters, typeParameters,
            constructor.valueParameters, valueParameters
        ) ?: return null
        val context = ResolutionContext(
            constructor.selfType.clazz, constructor.selfType,
            false, returnType
        )
        return ResolvedConstructor(generics, constructor,context)
    }
}