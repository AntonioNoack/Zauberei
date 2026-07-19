package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.SpecialFieldNames
import me.anno.zauber.ast.rich.TokenListIndex
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.simple.ASTSimplifier.reorderResolveParameters
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauber.typeresolution.ValueParameterImpl
import me.anno.zauber.typeresolution.members.*
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.NonObjectClassType

/**
 * left.right
 * */
class DotExpression(
    val left: Expression,
    val typeParameters: List<Type>?,
    val right: Expression,
    scope: Scope, origin: Long
) : Expression(scope, origin) {

    companion object {
        fun Type.handleNOCTForCall(): Type {
            val self = resolvedName
            if (self is NonObjectClassType) {
                val companion = self.type.clazz.companionObject
                    ?: error("Expected $self to have companion object")
                return companion.typeWithArgs
            }
            return self
        }
    }

    init {
        if (right is DotExpression || right is EnsureNotNullExpression) {
            throw IllegalArgumentException("List of dot-expressions must be left-to-right, $this is right to left")
        }
    }

    override fun clone(scope: Scope) = DotExpression(
        left.clone(scope), typeParameters,
        right.clone(scope),
        scope, origin
    )

    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean {
        val contextI = context
            .withTargetType(null /* unknown */)
        return typeParameters == null ||
                left.hasLambdaOrUnknownGenericsType(contextI) ||
                right.hasLambdaOrUnknownGenericsType(contextI)
    }

    override fun needsBackingField(methodScope: Scope): Boolean {
        return left.needsBackingField(methodScope) ||
                right.needsBackingField(methodScope)
    }

    override fun splitsScope(): Boolean {
        return left.splitsScope() ||
                right.splitsScope()
    }

    override fun isResolved(): Boolean = false

    override fun toStringImpl(depth: Int): String {
        val base = left.toString(depth)
        val typeParams = if (typeParameters.isNullOrEmpty()) null else
            typeParameters.joinToString(", ", "<", ">") { it.toString(depth) }
        return if (left is MemberNameExpression || left is FieldExpression || left is DotExpression) {
            "$base$typeParams.${right.toString(depth)}"
        } else {
            "($base)$typeParams.${right.toString(depth)}"
        }
    }

    fun getBaseType(context: ResolutionContext): Type {
        return TypeResolution.resolveType(
            /* targetLambdaType seems not easily deductible */
            context.withTargetType(null),
            left,
        )
    }

    fun isFieldType(): Boolean {
        return when (right) {
            is MemberNameExpression,
            is FieldExpression,
            is UnresolvedFieldExpression -> true
            else -> false
        }
    }

    fun isMethodType(): Boolean {
        return right is CallExpression
    }

    fun resolveField(context: ResolutionContext, baseType: Type = getBaseType(context)): ResolvedField? {
        // println("resolveField(): LHS: $baseType, RHS: ${right.javaClass.simpleName}")
        when (right) {
            is MemberNameExpression -> {
                if (baseType is NonObjectClassType) {
                    return handleNOCTField(context, baseType, right.name)
                }
                return FieldResolver.resolveField(
                    context.withSelfType(baseType), scope,
                    right.name, right.nameAsImport, null, origin,
                )
            }
            is UnresolvedFieldExpression -> {
                if (baseType is NonObjectClassType) {
                    return handleNOCTField(context, baseType, right.name)
                }
                return FieldResolver.resolveField(
                    context.withSelfType(baseType), scope,
                    right.name, right.nameAsImport, null, origin,
                )
            }
            is FieldExpression -> {
                if (baseType is NonObjectClassType) {
                    return handleNOCTField(context, baseType, right.field.name)
                }
                return FieldResolver.resolveField(
                    context.withSelfType(baseType),
                    right.field, null, scope, origin,
                )
            }
            else -> throw NotImplementedError(
                "dot-operator with $right (${right.javaClass.simpleName}) in " +
                        TokenListIndex.resolveOrigin(origin)
            )
        }
    }

    fun resolveCallable(context: ResolutionContext, baseType: Type): ResolvedMember<*> {
        right as CallExpression
        val baseTypeI = baseType.handleNOCTForCall()
        when (val base = right.self) {
            is MemberNameExpression -> {
                val constructor = null
                // todo for lambdas, baseType must be known for their type to be resolved
                val valueParameters = resolveValueParameters(context, right.valueParameters)
                val context = context.withSelfType(baseTypeI)
                return MethodResolver.resolveCallable(
                    context, scope, base.name, base.nameAsImport, constructor,
                    right.typeParameters, valueParameters, origin,
                ) ?: MethodResolver.printScopeForMissingMethod(
                    context, this, base.name,
                    right.typeParameters, valueParameters
                )
            }
            is UnresolvedFieldExpression -> {
                val valueParameters = resolveValueParameters(context, right.valueParameters)
                val context1 = context.withSelfType(baseTypeI)
                val constructor = if (baseTypeI is ClassType) {
                    val innerClass = baseTypeI.clazz[ScopeInitType.AFTER_DISCOVERY].children.firstOrNull {
                        it.scopeType == ScopeType.INNER_CLASS && it.name == base.name
                    }
                    if (innerClass != null) {
                        val valueParam0 =
                            listOf(ValueParameterImpl(SpecialFieldNames.OUTER_FIELD_NAME, baseTypeI, false))
                        ConstructorResolver.findMemberInScopeImpl(
                            innerClass, base.name,
                            typeParameters, valueParam0 + valueParameters, context1, origin
                        )
                    } else null
                } else null
                // todo for lambdas, baseType must be known for their type to be resolved
                return MethodResolver.resolveCallable(
                    context1, scope, base.name, base.nameAsImport, constructor,
                    right.typeParameters, valueParameters, origin,
                ) ?: MethodResolver.printScopeForMissingMethod(
                    context1, this, base.name,
                    typeParameters, valueParameters
                )
            }
            else -> throw NotImplementedError("Resolve type of call $base (${base.javaClass.simpleName})")
        }
    }

    private fun findNOCTScope(baseType: NonObjectClassType, rightName: String): Scope {
        return baseType.type.clazz.children
            .firstOrNull { it.name == rightName && (it.isClassLike() || it.scopeType == ScopeType.ENUM_ENTRY) }
            ?: error("No valid object '${rightName}' found in ${baseType.type}")
    }

    fun handleNOCTField(
        context: ResolutionContext,
        baseType: NonObjectClassType,
        rightName: String
    ): ResolvedField {
        val child = findNOCTScope(baseType, rightName)
        if (child.isObjectLike() || child.scopeType == ScopeType.ENUM_ENTRY) {
            val field = child.objectField
                ?: error("Missing object-field for ${baseType.type}")
            return ResolvedField(
                field, context.withSpec(Specialization(field.scope, ParameterList.emptyParameterList())),
                scope, MatchScore.zero,
            )
        } else {
            TODO("return class-like instance")
        }
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        return resolve(context).resolveValueType(context)
    }

    override fun resolveImpl(context: ResolutionContext): Expression {
        val base = left.resolve(context)
        val baseType = getBaseType(context)
        when {
            isFieldType() -> {
                val field = resolveField(context, baseType)
                    ?: error("Unresolved field for field type: (${right.javaClass.simpleName}) $baseType dot $right in $scope")
                return ResolvedGetFieldExpression(base, field, scope, origin)
            }
            isMethodType() -> {
                right as CallExpression
                val callable = resolveCallable(context, baseType)
                if (callable.resolved !is MethodLike) {
                    error("Implement DotExpression with methodType, but field: $this")
                }
                val targetParams = callable.resolved.valueParameters
                val isConstrForInnerClass = callable is ResolvedConstructor &&
                        callable.resolved.scope.parent!!.scopeType == ScopeType.INNER_CLASS
                val valueParameters0 = right.valueParameters
                val valueParameters1 =
                    if (isConstrForInnerClass) {
                        val outer = NamedParameter(SpecialFieldNames.OUTER_FIELD_NAME, base)
                        listOf(outer) + valueParameters0
                    } else valueParameters0
                val valueParameters2 = reorderResolveParameters(context, valueParameters1, targetParams, scope, origin)
                return ResolvedCallExpression(base, null, callable, valueParameters2, scope, origin)
            }
            else -> throw NotImplementedError("Resolve DotExpression with type ${right.javaClass.simpleName}")
        }
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(left)
        callback(right)
    }
}