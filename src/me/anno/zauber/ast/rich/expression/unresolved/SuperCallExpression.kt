package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.expression.CallExpressionBase
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.SuperExpression
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauber.typeresolution.members.ConstructorResolver
import me.anno.zauber.typeresolution.members.ResolvedConstructor
import me.anno.zauber.types.Type

/**
 * Calls base<typeParams>(valueParams), without anything on the left
 * */
class SuperCallExpression(
    base: SuperExpression,
    typeParameters: List<Type>?,
    valueParameters: List<NamedParameter>,
    origin: Long
) : CallExpressionBase(
    base, typeParameters,
    valueParameters, base.scope, origin
) {

    companion object {
        private val LOGGER = LogManager.getLogger(SuperCallExpression::class)
    }

    override fun toStringImpl(depth: Int): String {
        val valueParameters = valueParameters.joinToString(", ", "(", ")") { it.toString(depth) }
        val base = self.toString(depth)
        return if (typeParameters != null && typeParameters.isEmpty()) {
            "$base$valueParameters"
        } else "$base<${typeParameters ?: "?"}>$valueParameters"
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return valueParameters.any { it.value.needsBackingField(methodScope) }
    }

    override fun clone(scope: Scope) = SuperCallExpression(
        self.clone(scope) as SuperExpression, typeParameters,
        valueParameters.map { NamedParameter(it.name, it.value.clone(scope)) }, origin
    )

    override fun splitsScope(): Boolean = false

    override fun resolveCallable(context: ResolutionContext): ResolvedConstructor {
        val typeParameters = typeParameters
        val valueParameters = resolveValueParameters(context, valueParameters)
        if (LOGGER.isInfoEnabled) LOGGER.info("Resolving call: ${self}<${typeParameters ?: "?"}>($valueParameters)")

        // base can be a constructor, field or a method
        // find the best matching candidate...

        self as SuperExpression
        val targetScope = self.label[ScopeInitType.AFTER_OVERRIDES]
        val name = targetScope.name
        if (LOGGER.isInfoEnabled) LOGGER.info("Find call[UFE] '$name' with nameAsImport=null, tp: $typeParameters, vp: $valueParameters")

        return ConstructorResolver.findMemberInScopeImpl(targetScope, name, typeParameters, valueParameters, context, origin)
            ?: error(
                "Could not resolve constructor $targetScope$valueParameters, " +
                    "candidates: ${targetScope.constructors0.map { constr -> constr.valueParameters.map { it.type } }}"
            )
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(self)
        for (param in valueParameters) callback(param.value)
    }
}