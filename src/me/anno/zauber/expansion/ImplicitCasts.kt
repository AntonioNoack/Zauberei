package me.anno.zauber.expansion

import me.anno.zauber.ast.rich.Flags.IMPLICIT
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInit
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import kotlin.collections.get

object ImplicitCasts {

    private val LOGGER = LogManager.getLogger(ImplicitCasts::class)

    val conversionMethodRegistrator = ScopeInit(ScopeInitType.CONVERSION_METHODS) { scope: Scope ->
        registerConversionMethods(scope)
    }

    private fun registerConversionMethods(scope: Scope) {
        val methods = scope.methods
        for (i in methods.indices) {
            val method = methods[i]
            if (method.flags.hasFlag(IMPLICIT)) {
                if (method.valueParameters.isNotEmpty()) {
                    LOGGER.warn("Conversion method $method must not have any value parameters")
                    continue
                }

                val targetType = method.resolveReturnType(Specialization.noSpecialization)
                if (targetType is ClassType) {
                    scope.implicitCastMethods[targetType] = method
                } else {
                    LOGGER.warn("$targetType is not a valid return type for a conversion method")
                }
            }
        }
    }


    fun Expression.implicitCastTo(targetParam: Parameter?, context: ResolutionContext): Expression {
        if (targetParam == null) return this

        val valueType = resolveValueType(context)
        if (valueType !is ClassType) return this

        val targetType = targetParam.type.resolvedName.specialize(context)
        val implicitMap = (valueType.clazz).implicitCastMethods[targetType] ?: return this

        val newSpec = context.specialization.withScope(implicitMap.scope)
        val resolvedImplicitMap = ResolvedMethod(implicitMap, context.withSpec(newSpec), scope, MatchScore.zero)
        return ResolvedCallExpression(this, null, resolvedImplicitMap, emptyList(), scope, origin)
    }

    fun Expression.implicitCastTo(targetType: Type?, context: ResolutionContext): Expression {
        if (targetType == null) return this

        val valueType = resolveValueType(context)
        if (valueType !is ClassType) return this

        val implicitMap = valueType.clazz[ScopeInitType.AFTER_OVERRIDES]
            .implicitCastMethods[targetType] ?: return this

        val newSpec = context.specialization.withScope(implicitMap.scope)
        val resolvedImplicitMap = ResolvedMethod(implicitMap, context.withSpec(newSpec), scope, MatchScore.zero)
        return ResolvedCallExpression(this, null, resolvedImplicitMap, emptyList(), scope, origin)
    }

}