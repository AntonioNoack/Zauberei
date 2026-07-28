package me.anno.zauber.typeresolution

import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.TypeResolution.typeToScope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type

data class ResolutionContext(
    /**
     * where the code is located, e.g. for resolving fields, or applying conditional type refinement
     * */
    val codeScope: Scope,
    /**
     * this must be set exactly iff we look for a field/method on a specific base type;
     * if this is specified, we MUST match it
     * if not, we can match any higher self, or imported/available objects
     * */
    val selfType: Type?,
    /**
     * any generic value should be defined in this specialization
     * for code generation and runtime; for IDEs, this may be incomplete
     * */
    val specialization: Specialization,
    /**
     * Whether something without type, like while(true){}, is supported;
     * This is meant to prevent assignments and while-loops in call arguments: AssignmentExpr will throw if !allowTypeless
     * */
    val allowTypeless: Boolean,
    val targetType: Type?,
    /**
     * This value is only set if we're currently resolving expressions for inlining calls.
     * Otherwise, just leave it empty
     * */
    val knownLambdas: Map</* from a parameter */ Field, /* lambda-like, e.g. ::plus or { 2 * it } */Expression>,
    /**
     * when methods are resolved, this is some extra 'this' possibilities;
     * these come from lambdas with self-type, e.g. S.()->Boolean
     * */
    val extensionThis: List<ExtensionThis>,
) {

    constructor(
        codeScope: Scope, selfType: Type?, allowTypeless: Boolean, targetType: Type?,
        knownLambdas: Map<Field, Expression> = emptyMap(),
        extensionThis: List<ExtensionThis> = emptyList(),
    ) : this(
        codeScope, selfType, Specialization.noSpecialization, allowTypeless,
        targetType, knownLambdas, extensionThis
    )

    constructor(
        codeScope: Scope,
        selfType: Type?,
        specialization: Specialization,
        allowTypeless: Boolean,
        targetType: Type?
    ) :
            this(codeScope, selfType, specialization, allowTypeless, targetType, emptyMap(), emptyList())

    val selfScope = typeToScope(selfType)

    fun withTargetType(newTargetType: Type?): ResolutionContext {
        if (newTargetType == targetType) return this
        return ResolutionContext(
            codeScope, selfType, specialization, allowTypeless,
            newTargetType, knownLambdas, extensionThis
        )
    }

    fun withSelfType(newSelfType: Type?): ResolutionContext {
        if (newSelfType == selfType) return this
        return ResolutionContext(
            codeScope, newSelfType?.specialize(specialization), specialization, allowTypeless,
            targetType, knownLambdas, extensionThis
        )
    }

    fun withAllowTypeless(newAllowTypeless: Boolean): ResolutionContext {
        if (allowTypeless == newAllowTypeless) return this
        return ResolutionContext(
            codeScope, selfType, specialization, newAllowTypeless,
            targetType, knownLambdas, extensionThis
        )
    }

    fun withKnownLambdas(newKnownLambdas: Map<Field, Expression>): ResolutionContext {
        if (knownLambdas == newKnownLambdas) return this
        return ResolutionContext(
            codeScope, selfType, specialization, allowTypeless,
            targetType, newKnownLambdas, extensionThis
        )
    }

    fun withSpec(specialization: Specialization): ResolutionContext {
        if (this.specialization == specialization) return this
        return ResolutionContext(
            codeScope, selfType?.specialize(specialization), specialization, allowTypeless,
            targetType, knownLambdas, extensionThis
        )
    }

    fun withCodeScope(newCodeScope: Scope): ResolutionContext {
        if (this.codeScope == newCodeScope) return this
        return ResolutionContext(
            newCodeScope, selfType, specialization, allowTypeless,
            targetType, knownLambdas, extensionThis
        )
    }

    fun addExtensionThis(newExtensionThis: ExtensionThis): ResolutionContext {
        return ResolutionContext(
            codeScope, selfType, specialization, allowTypeless,
            targetType, knownLambdas, extensionThis + newExtensionThis
        )
    }

    override fun toString(): String {
        return "ResolutionContext(selfType=$selfType, " +
                "spec=$specialization, " +
                "allowTypeless=$allowTypeless, " +
                "targetType=$targetType, " +
                "knownLambdas=$knownLambdas, " +
                "extensionThis=$extensionThis)"
    }

    companion object {
        val minimal by threadLocal {
            // stores Specialization -> must be ThreadLocal
            ResolutionContext(root, null, false, null)
        }
    }
}