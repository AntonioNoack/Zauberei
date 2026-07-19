package me.anno.zauber.typeresolution.members

import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.utils.StringUtils.iff
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parser.DataClassGenerator.generateCopyMethodIfNeeded
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.typeresolution.members.FieldResolver.resolveField
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type

object MethodResolver : MemberResolver<Method, ResolvedMethod>() {

    private val LOGGER = LogManager.getLogger(MethodResolver::class)

    override fun findMemberInScope(
        scope: Scope?, origin: Long, name: String,

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        context: ResolutionContext
    ): ResolvedMethod? {
        scope ?: return null

        val afterOverrides = ScopeInitType.AFTER_OVERRIDES
        val scopeSelfType = scope.selfType
        if (name == "copy") {
            generateCopyMethodIfNeeded(scope, name, typeParameters, valueParameters, -1)
        }

        var bestMatch: ResolvedMethod? = null
        val methods = scope.getMethods(afterOverrides)
        for (i in methods.indices) {
            val method = methods[i]
            if (method.name != name) continue

            if (LOGGER.isInfoEnabled && method.typeParameters.isNotEmpty()) {
                LOGGER.info("Given $method in $context, can we deduct any generics from that?")
            }

            val returnType = context.targetType

            val methodReturnType0 = if (returnType != null) {
                getMethodReturnType(scopeSelfType, method)
            } else method.returnType // no resolution invoked (fast-path)
            val methodReturnType1 = methodReturnType0?.specialize(context)
            val selfType = context.selfType?.specialize(context)
            if (LOGGER.isInfoEnabled) LOGGER.info("MethodReturnType: $methodReturnType1 -> $returnType, selfType: $selfType")
            val match = FindMemberMatch.findMemberMatch(
                method, methodReturnType1, returnType,
                selfType, typeParameters, valueParameters,
                context.specialization,/* todo is this fine??? */scope, origin
            ) as? ResolvedMethod
            if (match != null && (bestMatch == null || match.matchScore < bestMatch.matchScore)) {
                bestMatch = match
            }
        }

        return bestMatch
    }

    fun getMethodReturnType(scopeSelfType: Type?, method: Method): Type? {
        if (method.returnType == null) {
            val selfType = method.selfType?.resolvedName ?: scopeSelfType
            if (LOGGER.isInfoEnabled) LOGGER.info("Resolving ${method.scope}.type by ${method.body}, selfType: $selfType")
            val context = ResolutionContext(method.body!!.scope, selfType, false, null, emptyMap())
            method.returnType = method.resolveReturnType(context)
        }
        return method.returnType
    }

    fun resolveCallable(
        context: ResolutionContext, codeScope: Scope,
        name: String, nameAsImport: List<Import>,
        constructor: ResolvedMember<*>?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        origin: Long
    ): ResolvedMember<*>? {
        return constructor
            ?: resolveMethod(
                context, codeScope,
                name, nameAsImport,
                typeParameters, valueParameters, origin
            )
            // todo value-parameters should be considered for fun-interfaces/LambdaTypes
            ?: resolveField(
                context, codeScope,
                name, nameAsImport,
                typeParameters, origin
            )
    }

    fun printScopeForMissingMethod(
        context: ResolutionContext, expr: Expression, name: String,
        typeParameters: List<Type>?, valueParameters: List<ValueParameter>
    ): Nothing {
        val selfScope = context.selfScope
        val codeScope = expr.scope
        val styledName = style(name, GREEN)
        val typeParams = typeParameters?.joinToString(", ", "<", ">") ?: "<?>"
        val valueParams = valueParameters.joinToString(", ", "(", ")")
        val prefix = if (selfScope != null) "$selfScope." else ""
        error(
            "Could not resolve method $prefix$styledName$typeParams$valueParams\n" +
                    "  Self-scope methods[$selfScope]: ${selfScope?.methods?.filter { it.name == name }}\n"
                        .iff(selfScope != null) +
                    "  Code-scope methods[$codeScope]: ${codeScope.methods.filter { it.name == name }}\n"
                        .iff(codeScope != selfScope) +
                    "  Lang-scope methods[$langScope]: ${langScope.methods.filter { it.name == name }}\n"
                        .iff(langScope != selfScope && langScope != codeScope) +
                    "  at ${resolveOrigin(expr.origin)}"
        )
    }

    fun resolveMethod(
        context: ResolutionContext, codeScope: Scope,
        name: String, nameAsImport: List<Import>,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        origin: Long
    ): ResolvedMember<*>? {
        return resolveInCodeScope(context, codeScope) { scope, selfType ->
            findMemberInScope(
                scope, origin, name, context.targetType,
                selfType, typeParameters, valueParameters, context
            )
        } ?: resolveByImports(
            context, name, nameAsImport,
            typeParameters, valueParameters, origin
        )
    }

    fun resolveByImports(
        context: ResolutionContext,
        name: String, nameAsImport: List<Import>,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        origin: Long
    ): ResolvedMember<*>? {
        for (import in nameAsImport) {
            if (import.name != name) continue
            val resolved = resolveByImport(
                context, import.path,
                typeParameters, valueParameters, origin
            )
            if (resolved != null) return resolved
        }
        return null
    }

    fun resolveByImport(
        context: ResolutionContext,
        nameAsImport: Scope,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        origin: Long
    ): ResolvedMember<*>? {

        val methodOwner = nameAsImport.parent
        if (methodOwner != null) {
            val methodSelfType = if (methodOwner.isObject())
                methodOwner.typeWithArgs else context.selfType
            val importedMethod = findMemberInScope(
                methodOwner, origin, nameAsImport.name, context.targetType, methodSelfType,
                typeParameters, valueParameters, context
            )
            if (importedMethod != null) return importedMethod

            val ownerCompanion = methodOwner.companionObject
            if (ownerCompanion != null) {
                val companionSelfType = ownerCompanion.typeWithArgs
                val importedCompanionMethod = findMemberInScope(
                    ownerCompanion, origin, nameAsImport.name, context.targetType, companionSelfType,
                    typeParameters, valueParameters, context
                )
                if (importedCompanionMethod != null) return importedCompanionMethod
            }
        }

        val importedConstructor = ConstructorResolver.findMemberInScopeImpl(
            nameAsImport, nameAsImport.name,
            typeParameters, valueParameters, context, origin
        )
        if (importedConstructor != null) return importedConstructor

        return null
    }

    fun null1(): ResolvedField? {
        return null
    }
}