package me.anno.zauber.ast.rich.member

import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.ORANGE
import me.anno.utils.StringStyles.YELLOW
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.FlagSet
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.resolved.SuperExpression
import me.anno.zauber.ast.rich.expression.unresolved.SuperCallExpression
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.expansion.IsMethodRecursive
import me.anno.zauber.expansion.IsMethodThrowing
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.GenericType

open class MethodLike(
    selfType: Type?,
    explicitSelfType: Boolean,

    typeParameters: List<Parameter>,
    valueParameters: List<Parameter>,
    returnType: Type?,

    scope: Scope, name: String,
    var body: Expression?,
    flags: FlagSet,
    origin: Long
) : Member(
    selfType, explicitSelfType, name, scope, flags,
    typeParameters, valueParameters, returnType, origin
) {

    companion object {
        private val LOGGER = LogManager.getLogger(MethodLike::class)
    }

    init {
        check(scope.isMethodLike()) {
            "Method scope must be method-like, got $scope (${scope.scopeType})"
        }
    }

    val capturedFields = HashSet<Field>()

    override val ownerScope get() = scope.parent!!
    override val memberScope get() = scope

    fun isRecursive(specialization: Specialization): Boolean {
        check(specialization.scope == memberScope)
        return IsMethodRecursive[specialization]
    }

    fun getThrownType(specialization: Specialization): Type {
        check(specialization.scope == memberScope)
        // println("Getting thrown type for $specialization")
        return IsMethodThrowing[specialization]
    }

    fun getYieldedType(specialization: Specialization): Type {
        check(specialization.scope == memberScope)
        return IsMethodThrowing[specialization]
    }

    fun getSpecializedBody(specialization: Specialization): Expression? {
        val body = body ?: return null
        val specialization = specialization.withScope(scope)
        // println("applying $specialization to $this")

        return specializations.getOrPut(specialization) {
            specialization.use {
                val context = createContext(specialization)
                val resolvedBody = body.resolve(context)
                val superCall = (this as? Constructor)?.superCall
                if (superCall != null) {
                    val part1 = resolveSuperCall(superCall, context)
                    ExpressionList(scope, origin, part1, resolvedBody)
                } else {
                    resolvedBody
                }
            }
        }
    }

    private fun resolveSuperCall(superCall: InnerSuperCall, context: ResolutionContext): Expression {
        val isThis = superCall.target == InnerSuperCallTarget.THIS
        val superCallType = ownerScope.superCalls.first { it.isClassCall }.type
        val scope = if (isThis) ownerScope else superCallType.clazz
        return SuperCallExpression(
            SuperExpression(scope, isThis, this.scope, superCall.origin),
            superCallType.typeParameters, superCall.valueParameters, superCall.origin
        ).resolve(context)
    }

    fun createContext(specialization: Specialization): ResolutionContext {
        val specialization = specialization.withScope(scope)
        return ResolutionContext(scope, null, specialization, true, null)
    }

    fun resolveReturnType(specialization: Specialization): Type {
        val specialization = specialization.withScope(scope)
        val returnType = returnType
        if (returnType != null) {
            return returnType.specialize(specialization)
        }

        var body = getSpecializedBody(specialization)
            ?: error("Either body or returnType must be defined for $this")
        while (body is ReturnExpression) body = body.value
        return body.resolveValueType(createContext(specialization))
    }

    val specializations = HashMap<Specialization, Expression>()

    /**
     * Whether the storage location (field/argument) is needed to resolve this's return type
     * todo use this property in CallExpression.hasLambdaOrUnderdefined
     * */
    val hasUnderdefinedGenerics by lazy {
        typeParameters.any { typeParam ->
            if (typeParam.scope == scope) {
                val genericType = GenericType(typeParam.scope, typeParam.name)
                returnType?.contains(genericType)
                    ?: ((selfType?.contains(genericType) == true) ||
                            valueParameters.none { it.type.contains(genericType) })
            } else false // else resolved by parent
        }
    }

    fun isPrivate(): Boolean = flags.hasFlag(Flags.PRIVATE)
    fun isProtected(): Boolean = flags.hasFlag(Flags.PROTECTED)
    fun isExternal(): Boolean = flags.hasFlag(Flags.EXTERNAL)
    fun isOverride(): Boolean = flags.hasFlag(Flags.OVERRIDE)
    fun isFinal(): Boolean = flags.hasFlag(Flags.FINAL)
    fun isAbstract(): Boolean = flags.hasFlag(Flags.ABSTRACT) || (ownerScope.isInterface() && body == null)

    fun isOpen(): Boolean = flags.hasFlag(Flags.OPEN) || flags.hasFlag(Flags.ABSTRACT) ||
            (flags.hasFlag(Flags.OVERRIDE) && !flags.hasFlag(Flags.FINAL)) ||
            ownerScope.isInterface()

    fun hasNoBody(): Boolean {
        scope[ScopeInitType.AFTER_DISCOVERY]
        return body == null
    }

    var selfTypeIfNecessary: Type? = null

    fun flags(builder: StringBuilder = StringBuilder()): StringBuilder {
        if (isPrivate()) builder.append(style("private ", ORANGE))
        if (isProtected()) builder.append(style("protected ", ORANGE))
        if (isExternal()) builder.append(style("external ", ORANGE))
        if (isAbstract()) builder.append(style("abstract ", ORANGE))
        if (isOverride()) builder.append(style("override ", ORANGE))
        if (isFinal()) builder.append(style("protected ", ORANGE))
        return builder
    }

    fun appendSelfType(builder: StringBuilder = StringBuilder()): StringBuilder {
        if (selfType != null) {
            builder.append(selfType).append('.')
        }
        return builder
    }

    fun appendTypeParams(builder: StringBuilder = StringBuilder()): StringBuilder {
        if (typeParameters.isNotEmpty()) {
            builder.append('<')
            builder.append(typeParameters.joinToString(", ") {
                if (it.type == Types.NullableAny) style(it.name, GREEN)
                else "${style(it.name, GREEN)}: ${it.type}"
            })
            builder.append("> ")
        }
        return builder
    }

    fun appendValueParams(builder: StringBuilder = StringBuilder()): StringBuilder {
        builder.append(valueParameters.joinToString(", ", "(", ")") {
            "${style(it.name, YELLOW)}: ${it.type}"
        })
        return builder
    }

    fun resolveThrownType(context: ResolutionContext): Type {
        val returnType = thrownType // todo if this is defined, validate it in all overrides & self
        if (returnType != null) return returnType

        val body = body ?: run {
            if (!isAbstract()) LOGGER.warn("Expected method to have either 'throws' or body $this")
            return Types.Throwable
        }
        return body.resolveThrownType(context)
    }

    fun resolveYieldedType(context: ResolutionContext): Type {
        val returnType = yieldedType // todo if this is defined, validate it in all overrides & self
        if (returnType != null) return returnType

        val body = body ?: run {
            if (!isAbstract()) LOGGER.warn("Expected method to have either 'yields' or body $this")
            return Types.Yielded
        }
        return body.resolveYieldedType(context)
    }

}