package me.anno.zauber.typeresolution.members

import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type

class ResolvedMethod(method: Method, context: ResolutionContext, codeScope: Scope, matchScore: MatchScore) :
    ResolvedMember<Method>(method, context, codeScope, matchScore) {

    val returnType get() = resolved.returnType?.specialize(specialization)

    override fun getScopeOfResolved(): Scope = resolved.scope

    private val valueType = lazy {
        specialize(resolved.resolveReturnType(context))
    }

    private val thrownType = lazy {
        specialize(resolved.resolveThrownType(context))
    }

    private val yieldedType = lazy {
        specialize(resolved.resolveYieldedType(context))
    }

    override fun resolveValueType(): Type {
        return valueType.value
    }

    override fun resolveThrownType(): Type {
        return thrownType.value
    }

    override fun resolveYieldedType(): Type {
        return yieldedType.value
    }

    override fun toString(): String {
        return if (specialization.isEmpty()) {
            "ResolvedMethod($resolved)"
        } else {
            "ResolvedMethod(method=$resolved, generics=$specialization)"
        }
    }
}