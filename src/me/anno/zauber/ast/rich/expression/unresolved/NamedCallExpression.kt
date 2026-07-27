package me.anno.zauber.ast.rich.expression.unresolved

import me.anno.zauber.SpecialFieldNames.OUTER_FIELD_NAME
import me.anno.zauber.ast.rich.expression.CallExpressionBase
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.unresolved.DotExpression.Companion.handleNOCTForCall
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauber.typeresolution.ValueParameterImpl
import me.anno.zauber.typeresolution.members.ConstructorResolver
import me.anno.zauber.typeresolution.members.MethodResolver
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

class NamedCallExpression(
    base: Expression,
    val name: String, val nameAsImport: List<Import>,
    typeParameters: List<Type>?, valueParameters: List<NamedParameter>,
    scope: Scope, origin: Long
) : CallExpressionBase(
    base, typeParameters,
    valueParameters, scope, origin
) {

    constructor(base: Expression, name: String, scope: Scope, origin: Long) :
            this(base, name, emptyList(), null, emptyList(), scope, origin)

    constructor(base: Expression, name: String, nameAsImport: List<Import>, scope: Scope, origin: Long) :
            this(base, name, nameAsImport, null, emptyList(), scope, origin)

    constructor(base: Expression, name: String, other: Expression, scope: Scope, origin: Long) :
            this(base, name, emptyList(), null, listOf(NamedParameter(other)), scope, origin)

    init {
        check(name != ".")
        check(name != "?.")
    }

    override fun clone(scope: Scope) = NamedCallExpression(
        self.clone(scope), name, nameAsImport, typeParameters,
        valueParameters.map { NamedParameter(it.name, it.value.clone(scope)) },
        scope, origin
    )

    override fun needsBackingField(methodScope: Scope): Boolean {
        return self.needsBackingField(methodScope) ||
                valueParameters.any { it.value.needsBackingField(methodScope) }
    }

    override fun splitsScope(): Boolean {
        return self.splitsScope() ||
                valueParameters.any { it.value.splitsScope() }
    }

    override fun toStringImpl(depth: Int): String {
        val base = self.toString(depth)
        val valueParameters = valueParameters.joinToString(", ", "(", ")") { it.toString(depth) }
        return if (typeParameters != null && typeParameters.isEmpty()) {
            "($base).$name$valueParameters"
        } else {
            "($base).$name<${typeParameters?.joinToString() ?: "?"}>$valueParameters"
        }
    }

    fun calculateBaseType(context: ResolutionContext): Type {
        return TypeResolution.resolveType(
            /* base type seems not deductible -> todo we could help it, if all candidates have the same base type */
            context.withTargetType(null),
            self,
        )
    }

    override fun resolveCallable(context: ResolutionContext): ResolvedMember<*> {
        val baseType = calculateBaseType(context).handleNOCTForCall()
        val valueParameters = resolveValueParameters(context, valueParameters)

        // println("Resolving callable $name, baseType: ${baseType.javaClass.simpleName}")
        val constructor = if (baseType is ClassType) {
            val innerClass = baseType.clazz[ScopeInitType.AFTER_DISCOVERY].children.firstOrNull { child ->
                child.scopeType == ScopeType.INNER_CLASS && child.name == name
            }
            if (innerClass != null) {
                // println("Inner class for $baseType: $innerClass")
                val selfValueParameter = ValueParameterImpl(OUTER_FIELD_NAME, baseType, false)
                ConstructorResolver.findMemberInScopeImpl(
                    innerClass, name, typeParameters,
                    listOf(selfValueParameter) + valueParameters, context, origin
                )
            } else null
        } else null

        val contextI = context.withSelfType(baseType)
        // println("Resolving callable with baseType $baseType")

        // check for missing type-parameters:
        if (baseType is ClassType && baseType.clazz.typeParameters.isNotEmpty() && baseType.typeParameters == null) {
            println("self: $self (${self.javaClass.simpleName})")
            if (self is FieldExpression) {
                println("self-type: ${self.field.valueType}")
                if (self.field.name == "content" && self.field.valueType == Types.Array) {
                    error("Expected ${self.field}: ${self.field.valueType} to have type parameters")
                }
            }
            error("Missing type parameters for $baseType in $this, $context")
        }

        // println("Resolving callable $name, baseType: $baseType, constructor: $constructor")

        return MethodResolver.resolveCallable(
            contextI, scope, name, nameAsImport, constructor,
            typeParameters, valueParameters, origin
        ) ?: MethodResolver.printScopeForMissingMethod(
            contextI, this, name,
            typeParameters, valueParameters,
        )
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        callback(self)
        for (param in valueParameters) callback(param.value)
    }

    override fun replaceLambdaFieldsWithClassFields(oldFields: List<Field>, newFields: List<Field>): Expression {
        return NamedCallExpression(
            self.replaceLambdaFieldsWithClassFields(oldFields, newFields),
            name, nameAsImport, typeParameters,
            valueParameters.map {
                NamedParameter(it.name, it.value.replaceLambdaFieldsWithClassFields(oldFields, newFields))
            }, scope, origin
        )
    }
}