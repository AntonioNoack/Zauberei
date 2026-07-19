package me.anno.zauber.typeresolution

import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.Zauber.STDLIB_NAME
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.unresolved.ArrayToVarargsStar
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.*
import me.anno.zauber.types.impl.arithmetic.AndType
import me.anno.zauber.types.impl.arithmetic.NotType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import me.anno.zauber.types.impl.unresolved.UnresolvedClassType
import me.anno.zauber.types.impl.unresolved.UnresolvedType

/**
 * Resolve types step by step, might fail, but should be stable at least.
 * */
object TypeResolution {

    private val LOGGER = LogManager.getLogger(TypeResolution::class)

    // todo make this depend on which language we currently parse?
    val langScope by threadLocal { root.getOrPut(STDLIB_NAME, null) }

    var depth = 0

    /**
     * resolve the type for a given expression
     * */
    fun resolveType(context: ResolutionContext, expr: Expression): Type {
        val withLogging = LOGGER.isInfoEnabled
        if (withLogging) LOGGER.info("[${++depth}] Resolving type of (${expr.javaClass.simpleName}) $expr (targetType=${context.targetType})")
        val type = expr.resolveValueType(context).resolvedName.specialize(context)
        if (withLogging) LOGGER.info("[${depth--}] Resolved type of $expr (${expr.javaClass.simpleName}) to $type (${type.javaClass.simpleName})")
        // if (type == Types.Nothing) error("Testing: bad type? $type")
        return type
    }

    fun resolveValueParameters(
        context: ResolutionContext,
        base: List<NamedParameter>,
        selfScope: Scope? = null,
    ): List<ValueParameter> {
        // target-type does not apply to parameters
        val contextI = context.withTargetType(null)
        return base.map { param ->
            val hasVarargStar = param.value is ArrayToVarargsStar
            if (param.value.hasLambdaOrUnknownGenericsType(contextI)) {
                LOGGER.info("Underdefined generics in $param :/")
                UnderdefinedValueParameter(param, contextI, hasVarargStar)
            } else {
                val type = resolveType(contextI, param.value).resolve(selfScope)
                ValueParameterImpl(param.name, type, hasVarargStar)
            }
        }
    }

    fun resolveThisScope(scope0: Scope): Scope {
        var scope = scope0
        while (true) {
            LOGGER.info("Checking ${scope.pathStr}/${scope.scopeType} for 'this'")
            when {
                scope.isClassLike() || scope.isMethodLike() -> return scope
                else -> {}
            }
            scope = scope.parentIfSameFile
                ?: error("Failed to resolve 'this' in $scope0")
        }
    }

    fun resolveThisType(context: ResolutionContext, scope: Scope): Type {
        // todo we must also insert any known generics...
        var scopeI = scope
        while (true) {
            LOGGER.info("Checking ${scopeI.pathStr}/${scopeI.scopeType} for 'this'")
            when {
                scopeI.isClassLike() -> {
                    val base = scopeI.typeWithArgs2
                    return if (scopeI.isObjectLike()) base else NonObjectClassType(base)
                }
                scopeI.isMethodLike() -> {
                    val func = scopeI.selfAsMethod
                    val self = func?.selfType?.specialize(context)
                    if (self != null) {
                        val selfScope = typeToScope(self)!!
                        LOGGER.info("Method-SelfType[${scopeI.pathStr}/spec=${context.specialization}]: $self -> $selfScope")
                        return resolveThisScope(selfScope).typeWithArgs
                    } else if (func != null) {
                        return scopeI.typeWithArgs
                    }
                }
                else -> {}
            }
            scopeI = scopeI.parent
                ?: error("Failed to resolve SelfType in $scope")
        }
    }

    fun findType(
        scope: Scope, // 2nd, recursive as long as fileName == parentScope.fileName
        selfScope: Type?, // 1st, surface-level only
        name: String
    ): Type? = findType(typeToScope(selfScope), name) ?: findType(scope, name)

    fun typeToScope(type: Type?): Scope? {
        return when (type) {
            null, NullType -> null
            is NotType -> null
            is ComptimeValue -> typeToScope(type.type)
            is ClassType -> type.clazz
            is UnionType -> {
                val scopes = type.types.mapNotNull { typeToScope(it) }
                if (scopes.distinct().size == 1) scopes.first()
                else null
            }
            is AndType -> {
                val scopes = type.types.mapNotNull { typeToScope(it) }
                if (scopes.distinct().size == 1) scopes.first()
                else null
            }
            is GenericType -> typeToScope(type.superBounds) // or should we choose null?
            is UnresolvedType -> typeToScope(type.resolve())
            is UnresolvedClassType -> type.clazz
            // is NullableType -> typeToScope(type.base)
            is NonObjectClassType -> type.type.clazz
            is LambdaType -> type.toScope()
            else -> throw NotImplementedError("typeToScope($type, ${type.javaClass.simpleName})")
        }
    }

    fun findType(scope: Scope?, name: String): Type? {
        var scope = scope ?: return null
        while (true) {

            val selfMatch = scope.children
                .firstOrNull { it.name == name && it[ScopeInitType.AFTER_DISCOVERY].isClassLike() }

            if (selfMatch != null) {
                val typeParams: List<Type>? =
                    if (selfMatch.hasTypeParameters && selfMatch.typeParameters.isEmpty()) emptyList() else null
                return ClassType(selfMatch, typeParams, -1)
            }

            val genericsMatch = scope.resolveGenericType(name)
            if (genericsMatch != null) return GenericType(genericsMatch.scope, genericsMatch.name)

            scope = scope.parentIfSameFile ?: return null
        }
    }
}