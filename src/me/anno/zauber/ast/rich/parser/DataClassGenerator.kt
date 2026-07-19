package me.anno.zauber.ast.rich.parser

import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.controlflow.ShortcutOperator
import me.anno.zauber.ast.rich.controlflow.shortcutExpressionI
import me.anno.zauber.ast.rich.expression.CheckEqualsOp
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.IsInstanceOfExpr
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.unresolved.ConstructorExpression
import me.anno.zauber.ast.rich.expression.unresolved.DotExpression
import me.anno.zauber.ast.rich.expression.unresolved.FieldExpression
import me.anno.zauber.ast.rich.expression.unresolved.NamedCallExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.ParameterMutability
import me.anno.zauber.ast.rich.parameter.ParameterType
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.ValueParameter
import me.anno.zauber.typeresolution.members.MethodResolver
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types

object DataClassGenerator {

    private val LOGGER = LogManager.getLogger(DataClassGenerator::class)

    private class SpecialValues {
        val i31 = NumberExpression("31", root, -1)
        val i1 = NumberExpression("1", root, -1)
        val iTrue = SpecialValueExpression(SpecialValue.TRUE, root, -1)
    }

    private val special by threadLocal { SpecialValues() }

    private const val KEYWORDS = Flags.SYNTHETIC or Flags.OVERRIDE
    private const val KEYWORDS_NO_OVERRIDE = KEYWORDS and Flags.OVERRIDE.inv()

    class ExpressionBuilder(var scope: Scope, val origin: Long, val type: Type) {
        var expr: Expression? = null

        fun times(value: Expression) {
            expr = NamedCallExpression(expr!!, "times", value, scope, origin)
                .apply { resolvedType = type }
        }

        fun plus(value: Expression) {
            expr = NamedCallExpression(expr!!, "plus", value, scope, origin)
                .apply { resolvedType = type }
        }

        fun shortcutAnd(getCondition: (Scope) -> Expression): Scope {
            val expr = expr
            if (expr == null) {
                val scope = scope.getOrPut(scope.generateName("shortcut"), ScopeType.METHOD_BODY)
                this.expr = getCondition(scope)
                return scope
            }

            expr.resolvedType = Types.Boolean

            val trueScope = scope.getOrPut(scope.generateName("shortcut"), ScopeType.METHOD_BODY)
            val falseScope = scope.getOrPut(scope.generateName("shortcut"), ScopeType.METHOD_BODY)

            val condition = getCondition(trueScope)
            condition.resolvedType = Types.Boolean
            this.scope = trueScope
            this.expr = shortcutExpressionI(
                expr, ShortcutOperator.AND, condition,
                falseScope, origin
            )
            return trueScope
        }
    }

    fun Scope.findPrimaryFields(): List<Field> {
        return getOrCreatePrimaryConstructorScope()
            .selfAsConstructor!!
            .valueParameters
            .mapNotNull { it.field }
            .map { constructorField ->
                fields.first { constructorField.name == it.name }
            }
    }

    fun finishDataClass(classScope: Scope, origin: Long) {
        val primaryFields = classScope.findPrimaryFields()
        val context = ResolutionContext.minimal /// todo better context?

        ensureHashCodeMethod(classScope, primaryFields, origin)
        ensureToStringMethod(classScope, primaryFields, origin)
        ensureEqualsMethods(classScope, primaryFields, origin)
        ensureCopyMethod(classScope, primaryFields, context, origin)
    }

    private fun findMethod(classScope: Scope, name: String, typeParams: List<Type>): Method? {
        return classScope[ScopeInitType.AFTER_DISCOVERY].methods.firstOrNull { method ->
            method.name == name && method.typeParameters.size == typeParams.size &&
                    method.typeParameters.map { param -> param.type } == typeParams
        }
    }

    private fun ensureHashCodeMethod(classScope: Scope, primaryFields: List<Field>, origin: Long) {
        val hashCodeMethod = findMethod(classScope, "hashCode", emptyList())
        if (hashCodeMethod == null) {
            generateHashCodeMethod(classScope, primaryFields, origin)
        }
    }

    private fun ensureToStringMethod(classScope: Scope, primaryFields: List<Field>, origin: Long) {
        val toStringMethod = findMethod(classScope, "toString", emptyList())
        if (toStringMethod == null) {
            generateToStringMethod(classScope, origin, primaryFields)
        }
    }

    private fun ensureEqualsMethods(classScope: Scope, primaryFields: List<Field>, origin: Long) {
        val equalsAnyMethod = findMethod(classScope, "equals", listOf(Types.NullableAny))
        if (equalsAnyMethod == null) {
            generateEqualsAnyMethod(classScope, origin, primaryFields)
        }

        // this is an optimization:
        val equalsSelfMethod = findMethod(classScope, "equals", listOf(classScope.typeWithArgs))
        if (equalsSelfMethod == null) {
            // saves type-check and cast, and therefore should be much faster,
            //  and allow us to not runtime-allocate some instances
            generateEqualsSelfMethod(classScope, origin, primaryFields)
        }
    }

    private fun generateHashCodeMethod(
        classScope: Scope, primaryFields: List<Field>, origin: Long
    ) {
        val methodScope = classScope.generate("hashCode", ScopeType.METHOD)
        methodScope.setEmptyTypeParams()

        val builder = ExpressionBuilder(methodScope, origin, Types.Int)
        for (field in primaryFields) {
            val fieldExpr = FieldExpression(field, methodScope, origin)
            val hashExpr = NamedCallExpression(fieldExpr, "hashCode", methodScope, origin)
            if (builder.expr == null) {
                builder.expr = hashExpr
            } else {
                builder.times(special.i31)
                builder.plus(hashExpr)
            }
        }

        val body = ReturnExpression(builder.expr ?: special.i1, null, methodScope, origin)
        methodScope.selfAsMethod = Method(
            null, false, "hashCode", emptyList(), emptyList(),
            methodScope, Types.Int, emptyList(), body, KEYWORDS, origin
        )
    }

    private fun generateToStringMethod(
        classScope: Scope, origin: Long,
        primaryFields: List<Field>
    ) {
        val methodScope = classScope.generate("toString", ScopeType.METHOD)
        methodScope.setEmptyTypeParams()

        val builder = ExpressionBuilder(methodScope, origin, Types.String)
        builder.expr = StringExpression("${classScope.name}(", methodScope, origin)
        for ((i, field) in primaryFields.withIndex()) {
            val fieldExpr = FieldExpression(field, methodScope, origin)
            val stringExpr = NamedCallExpression(fieldExpr, "toString", methodScope, origin)
            val name = field.name
            builder.plus(StringExpression(if (i > 0) ",$name=" else "$name=", methodScope, origin))
            builder.plus(stringExpr)
        }
        builder.plus(StringExpression(")", methodScope, origin))

        val body = ReturnExpression(builder.expr!!, null, methodScope, origin)
        methodScope.selfAsMethod = Method(
            null, false, "toString", emptyList(), emptyList(),
            methodScope, Types.String, emptyList(), body, KEYWORDS, origin
        )
    }

    private fun generateEqualsAnyMethod(
        classScope: Scope, origin: Long,
        primaryFields: List<Field>
    ) {
        val methodScope = classScope.generate("equals", ScopeType.METHOD)
        methodScope.setEmptyTypeParams()

        val parameter = Parameter(
            0, "other", ParameterType.VALUE_PARAMETER,
            ParameterMutability.DEFAULT,
            Types.NullableAny, methodScope, origin,
        )
        val otherField = parameter.getOrCreateField(null, Flags.NONE)

        val builder = ExpressionBuilder(methodScope, origin, Types.Boolean)
        val otherInstanceExpr0 = FieldExpression(otherField, methodScope, origin)
        builder.expr = IsInstanceOfExpr(otherInstanceExpr0, classScope.typeWithArgs, methodScope, origin)

        for (field in primaryFields) {
            builder.shortcutAnd { subScope ->
                val fieldExpr = FieldExpression(field, subScope, origin)
                val otherInstanceI = FieldExpression(otherField, subScope, origin) // must be here for implicit cast
                val otherFieldExpr = DotExpression(otherInstanceI, emptyList(), fieldExpr, subScope, origin)
                CheckEqualsOp(
                    fieldExpr, otherFieldExpr,
                    byPointer = false, negated = false,
                    null, subScope, origin
                )
            }
        }

        val body = ReturnExpression(builder.expr ?: special.iTrue, null, methodScope, origin)
        methodScope.selfAsMethod = Method(
            null, false, "equals", emptyList(), listOf(parameter),
            methodScope, Types.Boolean, emptyList(), body, KEYWORDS, origin
        )
    }

    private fun generateEqualsSelfMethod(
        classScope: Scope, origin: Long,
        primaryFields: List<Field>
    ) {
        val methodScope = classScope.generate("equals", ScopeType.METHOD)
        methodScope.setEmptyTypeParams()

        val parameter = Parameter(
            0, "other", ParameterType.VALUE_PARAMETER,
            ParameterMutability.DEFAULT,
            classScope.typeWithArgs, methodScope, origin
        )
        val otherField = parameter.getOrCreateField(null, Flags.NONE)

        val builder = ExpressionBuilder(methodScope, origin, Types.Boolean)
        for (field in primaryFields) {
            builder.shortcutAnd { subScope ->
                val fieldExpr = FieldExpression(field, subScope, origin)
                val otherInstanceI = FieldExpression(otherField, subScope, origin) // must be here for implicit cast
                val otherFieldExpr = DotExpression(otherInstanceI, emptyList(), fieldExpr, subScope, origin)
                CheckEqualsOp(
                    fieldExpr, otherFieldExpr,
                    byPointer = false, negated = false,
                    null, subScope, origin
                )
            }
        }

        val body = ReturnExpression(builder.expr ?: special.iTrue, null, methodScope, origin)
        methodScope.selfAsMethod = Method(
            null, false, "equals", emptyList(), listOf(parameter),
            methodScope, Types.Boolean, emptyList(), body, KEYWORDS_NO_OVERRIDE, origin
        )
    }

    private fun ensureCopyMethod(
        classScope: Scope, primaryFields: List<Field>,
        context: ResolutionContext, origin: Long
    ) {
        // create copy methods with zero fields
        val copyMethod = MethodResolver.findMemberInScope(
            classScope, origin, "copy", classScope.typeWithArgs, classScope.typeWithArgs,
            emptyList(), emptyList(), context
        )
        if (copyMethod == null) {
            generateCopyMethod(classScope, primaryFields, emptyList(), origin)
        }

        // create copy methods with just one field
        for (setField in primaryFields) {
            val valueType = setField.valueType!!.resolvedName
            val copyMethod = classScope.methods.firstOrNull { method ->
                method.name == "copy" && method.valueParameters.size == 1 &&
                        method.valueParameters[0].run {
                            name == setField.name && type.resolvedName == valueType
                        }
            }
            if (copyMethod == null) {
                if (LOGGER.isInfoEnabled) LOGGER.info("Creating copy(${setField.name}: $valueType) for $classScope")
                generateCopyMethod(classScope, primaryFields, listOf(setField), origin)
            }
        }
    }

    fun generateCopyMethodIfNeeded(
        classScope: Scope, name: String,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
        origin: Long
    ) {

        classScope[ScopeInitType.AFTER_DISCOVERY]

        // some preliminary checks
        if (name != "copy") return
        if (!typeParameters.isNullOrEmpty()) return
        if (!(classScope.isDataClass() || classScope.isValueType())) return
        if (valueParameters.size < 2) return // handled elsewhere
        if (valueParameters.any { vp -> vp.name == null }) return
        if (valueParameters.any { vp -> classScope.fields.none { field -> field.name == vp.name } }) return

        // last checks for validity
        val primaryFields = classScope.findPrimaryFields()
        val setFields = valueParameters.map { vp ->
            primaryFields.firstOrNull { field -> field.name == vp.name }
                ?: return
        }

        val existingMethod = classScope.children.firstOrNull {
            val m = it.selfAsMethod
            m != null && m.name == "copy" &&
                    m.typeParameters.isEmpty() &&
                    m.valueParameters.size == valueParameters.size &&
                    valueParameters.indices.all { index ->
                        m.valueParameters[index].name == valueParameters[index].name
                    }
        }

        if (existingMethod == null) {
            generateCopyMethod(classScope, primaryFields, setFields, origin)
        }
    }

    fun generateCopyMethod(
        classScope: Scope, primaryFields: List<Field>,
        setFields: List<Field>, origin: Long,
    ) {
        val methodScope = classScope.generate("copy", ScopeType.METHOD)
        methodScope.setEmptyTypeParams()

        val valueParams = setFields.mapIndexed { index, setField ->
            Parameter(
                index, setField.name, ParameterType.VALUE_PARAMETER,
                ParameterMutability.DEFAULT,
                setField.valueType!!, methodScope, origin
            ).apply { mustBeNamed = true }
        }

        val typeParameters = classScope.typeParameters.map { it.type }
        val valueParameters = primaryFields.map { field ->
            val valueParam = valueParams.firstOrNull { it.name == field.name }
            val expr = if (valueParam != null) {
                val paramField = valueParam
                    .getOrCreateField(null, Flags.SYNTHETIC)
                FieldExpression(paramField, methodScope, origin)
            } else {
                FieldExpression(field, classScope, origin)
            }
            NamedParameter(field.name, expr)
        }
        val copyExpr = ConstructorExpression(
            classScope, typeParameters,
            valueParameters, null, classScope, origin
        )

        val body = ReturnExpression(copyExpr, null, methodScope, origin)
        methodScope.selfAsMethod = Method(
            null, false, "copy",
            emptyList(), valueParams, methodScope,
            classScope.typeWithArgs, emptyList(),
            body, KEYWORDS_NO_OVERRIDE, origin
        )
    }
}