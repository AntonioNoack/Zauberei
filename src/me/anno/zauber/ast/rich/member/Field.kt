package me.anno.zauber.ast.rich.member

import me.anno.utils.MutableLazy
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.ast.FlagSet
import me.anno.zauber.ast.rich.Annotation
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasAnyFlag
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.TypeOfField
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes

class Field(
    scope: Scope,

    selfType: Type?, // may be null inside methods (self is stack) and on package level (self is static)
    explicitSelfType: Boolean,

    val isMutable: Boolean,
    var byParameter: Any?, // Parameter | LambdaParameter | null

    name: String,
    valueType: Type?,
    val initialValue: Expression?,

    flags: FlagSet,
    origin: Long
) : Member(
    selfType, explicitSelfType,
    name, scope, flags,
    emptyList(),
    emptyList(),
    valueType,
    origin
) {

    companion object {
        private val LOGGER = LogManager.getLogger(Field::class)

        fun cleanNameForCompilation(name: String): String {
            var name = name
            while (name.startsWith('$')) name = name.substring(1)
            if (name.isEmpty() || name.first() in '0'..'9') name = "_$name"
            return name
        }
    }

    var valueType
        get() = returnType
        set(value) {
            returnType = value
        }

    val isMutableEx get() = isMutable || scope.flags.hasFlag(Flags.VALUE)

    var isCaptured = false

    val annotations = ArrayList<Annotation>()

    /**
     * when compiling languages, who dislike shadowing,
     * create new names for duplicated ones
     * */
    var newName = cleanNameForCompilation(name)

    fun needsBackingField(): Boolean {
        val getterBody = getter?.body ?: return true
        if (getterBody.needsBackingField(getterBody.scope)) return true

        val setterBody = setter?.body ?: return false
        return setterBody.needsBackingField(setterBody.scope)
    }

    fun isBackingField(methodScope: Scope): Boolean {
        return name == "field" &&
                flags.hasFlag(Flags.SYNTHETIC) &&
                scope == methodScope
    }

    fun isBackingField(): Boolean {
        val field = getBackedField() ?: this
        return this != field
    }

    fun getBackedField(): Field? {
        if (name != "field") return null
        if (!flags.hasFlag(Flags.SYNTHETIC)) return null

        val scope = scope
        if (scope.scopeType != ScopeType.FIELD_GETTER &&
            scope.scopeType != ScopeType.FIELD_SETTER
        ) return null

        val method = scope.selfAsMethod
        return method?.backedField
    }

    var getter: Method? = null
    var setter: Method? = null

    var getterExpr: Expression? = null
    var hasCustomGetter = false
    var hasCustomSetter = false

    fun resolveValueType(context: ResolutionContext): Type {

        // validate context specialization, s.t. we have all type knowledge
        val fieldSpec = context.specialization.withScopeUnknownIfMissing(ownerScope)
        val context = context.withSpec(fieldSpec)

        val valueType = valueType
        if (valueType != null) {
            return valueType.resolvedName.specialize(context)
        }

        val initialValue = initialValue
        if (initialValue != null) {
            LOGGER.info("Resolving field ${selfType}.${name} using initialValue: $initialValue, codeScope: ${initialValue.scope}, selfScope: ${context.selfScope}")
            return TypeResolution.resolveType(context, initialValue)
        }

        var getterExpr = getterExpr
        while (getterExpr is ReturnExpression) {
            getterExpr = getterExpr.value
        }
        if (getterExpr != null) { // a block
            val newContext = context.withSelfType(selfType ?: context.selfType)
            val resolvedExpr = getterExpr.resolve(newContext)
            var type = TypeResolution.resolveType(newContext, resolvedExpr)
            if (type == Types.Nothing) {
                type = findReturnExprType(newContext, resolvedExpr)
            }
            return type
        }

        if (name == "field") {
            // todo check if inside getter/setter, if so, get the real field
            val scope = getGetterOrSetterScope()
            if (scope != null) {
                val getterOrSetter = scope.selfAsMethod!!
                val owner = scope.parent!!
                val matchingField = owner.fields
                    .firstOrNull { it.setter == getterOrSetter || it.getter == getterOrSetter }
                    ?: error("Missing getter/setter field in $scope for $this")
                return TypeOfField(matchingField, scope)
            }
        }

        error("Field $this at ${resolveOrigin(origin)} has neither type, nor initial/getter, cannot resolve type")
    }

    fun findReturnExprType(context: ResolutionContext, expression: Expression): Type {
        val foundTypes = ArrayList<Type>()
        expression.forEachExpressionRecursively { expr ->
            if (expr is ReturnExpression) {
                val value = expr.value
                val type = TypeResolution.resolveType(context, value)
                foundTypes.add(type)
            }
        }
        return unionTypes(foundTypes)
    }

    fun getGetterOrSetterScope(): Scope? {
        var scope = scope
        while (true) {
            when (scope.scopeType) {
                ScopeType.FIELD_GETTER,
                ScopeType.FIELD_SETTER -> return scope
                else -> scope = scope.parentIfSameFile ?: return null
            }
        }
    }

    override fun toString(): String {
        return toString(10)
    }

    fun toString(depth: Int): String {
        val self = selfType ?: ownerScope
        return if (initialValue == null || depth < 0) {
            toStringWithoutDefault()
        } else {
            "Field($self.${style(name, GREEN)}=${initialValue.toString(depth - 1)})"
        }
    }

    fun toStringWithoutDefault(): String {
        val self = selfType ?: ownerScope
        return "Field($self.${style(name, GREEN)})"
    }

    override val ownerScope get() = scope
    override val memberScope: Scope get() = fieldScope

    private val fieldScopeImpl = MutableLazy { createFieldScope() }

    private fun createFieldScope(): Scope {
        val scope = ownerScope.getOrPut(name, ScopeType.FIELD)
        check(scope.selfAsField == null || scope.selfAsField === this) {
            LOGGER.warn("Field1: ${resolveOrigin(scope.selfAsField!!.origin)}")
            LOGGER.warn("Field2: ${resolveOrigin(origin)}")
            "selfAsField is defined twice?? ${scope.selfAsField} vs $this"
        }
        scope.selfAsField = this
        return scope
    }

    val fieldScope: Scope
        get() {
            val solution = fieldScopeImpl.value
            assertEquals(solution.parent, ownerScope) {
                "Field was probably moved by a split"
            }
            return solution
        }

    fun moveToScope(newScope: Scope) {
        check(scope.fields.remove(this)) { "Failed to remove field from scope" }
        scope = newScope
        if (fieldScopeImpl.isInitialized()) {
            fieldScopeImpl.value.removeFromParent()
            fieldScopeImpl.value = createFieldScope()
        }
        newScope.addField(this)
    }

    fun isPrivate(): Boolean = this@Field.flags.hasFlag(Flags.PRIVATE)
    fun isLateinit(): Boolean = this@Field.flags.hasFlag(Flags.LATEINIT)
    fun isOpen(): Boolean {
        if (ownerScope.isInterface()) return true
        val isOpenField = this@Field.flags.hasAnyFlag(Flags.OPEN or Flags.OVERRIDE)
        val isOpenClass = ownerScope.isOpen()
        return isOpenField && isOpenClass
    }

    fun isObjectInstance(): Boolean {
        return name == OBJECT_FIELD_NAME &&
                ownerScope.objectField === this
    }

}