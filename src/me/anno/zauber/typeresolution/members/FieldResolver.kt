package me.anno.zauber.typeresolution.members

import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.resolveType
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type

object FieldResolver : MemberResolver<Field, ResolvedField>() {

    private val LOGGER = LogManager.getLogger(FieldResolver::class)

    override fun findMemberInScope(
        scope: Scope?, origin: Long, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext,
    ): ResolvedField? {
        scope ?: return null

        // println("Searching for '$name' in $scope, from ${resolveOrigin(origin)}")

        val selfType = context.selfType
        val returnType = context.targetType

        val scopeSelfType = scope.selfType
        var bestMatch: ResolvedField? = null
        for (field in scope.fields) {
            if (field.name != name) continue
            if (field.typeParameters.isNotEmpty()) {
                LOGGER.info("Given $field on $selfType, with target $returnType, can we deduct any generics from that?")
            }
            val valueType = getFieldReturnType(context.codeScope, scopeSelfType, field, returnType)
            val match = FindMemberMatch.findMemberMatch(
                field, valueType,
                returnType, selfType,
                typeParameters, valueParameters,
                context, origin
            ) as? ResolvedField
            bestMatch = joinMatches(bestMatch, match)
        }

        val companion = scope.companionObject
        if (companion != null) {
            if (scope.name == name) {
                val field = companion.getOrCreateObjectField(-1)
                val valueType = getFieldReturnType(context.codeScope, scopeSelfType, field, returnType)
                val match = FindMemberMatch.findMemberMatch(
                    field, valueType,
                    returnType, selfType,
                    typeParameters, valueParameters,
                    context.withCodeScope(scope), origin
                ) as? ResolvedField
                bestMatch = joinMatches(bestMatch, match)
            }
            val match = findMemberInScope(
                companion, origin, name, returnType, selfType,
                typeParameters, valueParameters, context,
            )
            bestMatch = joinMatches(bestMatch, match)
        }

        for (child in scope[ScopeInitType.AFTER_DISCOVERY].children) {
            if (child.name != name || !child.isObjectLike()) continue

            child[ScopeInitType.AFTER_DISCOVERY]

            val field = child.getOrCreateObjectField(-1)
            val valueType = getFieldReturnType(context.codeScope, scopeSelfType, field, returnType)
            val match = FindMemberMatch.findMemberMatch(
                field, valueType,
                returnType, selfType,
                typeParameters, valueParameters,
                context.withCodeScope(scope), origin
            ) as? ResolvedField
            bestMatch = joinMatches(bestMatch, match)
        }
        return bestMatch
    }

    fun joinMatches(bestMatch: ResolvedField?, match: ResolvedField?): ResolvedField? {
        return if (match != null && (bestMatch == null || match.matchScore < bestMatch.matchScore)) match
        else bestMatch
    }

    fun getFieldReturnType(codeScope: Scope, scopeSelfType: Type?, field: Field, returnType: Type?): Type? {
        return if (returnType != null) {
            getFieldReturnType(codeScope, scopeSelfType, field)
        } else field.valueType // no resolution invoked (fast-path)
    }

    private fun getFieldReturnType(codeScope: Scope, scopeSelfType: Type?, field: Field): Type? {
        if (field.valueType == null) {
            var expr = (field.initialValue ?: field.getterExpr)!!
            while (expr is ReturnExpression) expr = expr.value
            LOGGER.info("Resolving valueType($field), initial/getter: $expr")
            val contextSelfType = field.selfType ?: scopeSelfType
            val context = ResolutionContext(codeScope, contextSelfType, false, null, emptyMap())
            field.valueType = resolveType(context, expr)
        }
        return field.valueType
    }

    fun resolveField(
        context: ResolutionContext, codeScope: Scope,
        name: String, nameAsImport: List<Import>,
        typeParameters: List<Type>?, // if provided, typically not the case (I've never seen it)
        origin: Long,
    ): ResolvedField? {
        val selfType = context.selfType
        LOGGER.info(
            "TypeParams for field '$name': $typeParameters, " +
                    "scope: $codeScope, selfType: $selfType, " +
                    "targetType: ${context.targetType}, spec: ${context.specialization}"
        )

        return resolveInCodeScope(context, codeScope) { candidateScope, selfType ->
            findMemberInScope(
                candidateScope, origin, name,
                typeParameters, emptyList(),
                context.withSelfType(selfType)
            )
        } ?: resolveFieldByImports(
            context, codeScope,
            name, nameAsImport,
            typeParameters, origin
        ) ?: resolveFieldByPackages(
            context, name, origin
        )
    }

    fun resolveFieldByImports(
        context: ResolutionContext, codeScope: Scope,
        name: String, nameAsImport: List<Import>,
        typeParameters: List<Type>?, // if provided, typically not the case (I've never seen it)
        origin: Long,
    ): ResolvedField? {
        if (nameAsImport.isEmpty()) return null

        val selfTypes = ArrayList<Type?>()
        resolveInCodeScope(context, codeScope) { _, selfType ->
            if (selfType !in selfTypes) selfTypes.add(selfType)
        }
        if (null !in selfTypes) selfTypes.add(null)

        // println("Checking imports $nameAsImport, selfType: ${context.selfType}, selfTypes: $selfTypes")
        val returnType = context.targetType
        for (import in nameAsImport) {
            if (import.name != name) continue
            val importPath = import.path
            val originalName = importPath.name
            val isUnknownScope = importPath.scopeType == null
            val scope = if (isUnknownScope) importPath.parent else importPath

            for (selfType in selfTypes) {
                val field = findMemberInScope(
                    scope, origin, originalName, returnType, selfType,
                    typeParameters, emptyList(), context
                )
                if (field != null) return field
            }
        }
        return null
    }

    fun resolveFieldByPackages(context: ResolutionContext, name: String, origin: Long): ResolvedField? {
        if (context.selfType != null) return null
        val root = root[ScopeInitType.AFTER_DISCOVERY]
        // println("looking up $name in root")
        return findMemberInScope(root, origin, name, null, emptyList(), context)
    }

    fun resolveField(
        context: ResolutionContext, field: Field,
        typeParameters: List<Type>?, // if provided, typically not the case (I've never seen it)
        scope: Scope, origin: Long,
    ): ResolvedField? {
        val selfType = context.selfType
        LOGGER.info("TypeParams for field '$field': $typeParameters, selfType: $selfType")

        val valueType = getFieldReturnType(context.codeScope, context.selfType, field, context.targetType)
        return FindMemberMatch.findMemberMatch(
            field, valueType,
            context.targetType, selfType,
            typeParameters, emptyList(),
            context.withCodeScope(scope), origin
        ) as? ResolvedField
    }

}