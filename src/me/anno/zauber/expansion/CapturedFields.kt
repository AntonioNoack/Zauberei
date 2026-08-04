package me.anno.zauber.expansion

import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.unresolved.FieldResolvable
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext

// todo test this class
// todo use this to find captured fields for ASTSimplifier:
//  val-fields become extra parameters
//  var-fields and functions are referenced by one extra method-reference-parameter
object CapturedFields : MethodOrClassColoring<Set<Field>>() {

    override fun getSelfColor(key: MethodOrClassSpecialization): Set<Field> {
        if (key.method != null) {
            val body = key.method.getSpecializedBody(key.specialization) ?: return emptySet()
            val capturedFields = HashSet<Field>()
            collectFields(body, key, capturedFields)
            return capturedFields
        } else {
            val clazz = key.clazz ?: return emptySet()
            clazz[ScopeInitType.AFTER_DISCOVERY]

            if (!clazz.isClassLike() || clazz.isObjectLike()) return emptySet()

            // find fields in init blocks
            val capturedFields = HashSet<Field>()
            val prim = clazz.getOrCreatePrimaryConstructorScope()
            for (expr in prim.code) {
                collectFields(expr, key, capturedFields)
            }
            return capturedFields
        }
    }

    private fun collectFields(body: Expression, moc: MethodOrClassSpecialization, capturedFields: HashSet<Field>) {
        val context = ResolutionContext(body.scope, moc.method?.selfType, false, null)
        // todo we only need to check the first element of each getter-chain, I think...
        body.forEachExpressionRecursively { expr ->
            if (expr is FieldResolvable) {
                // todo context depends on the expression, not just the method...
                // we need to look inside lambdas... do they invoke forEachExpressionRecursively? yes, they do
                // todo but that's more of an inner function...

                val field = expr.resolveField(context)?.resolved
                    ?: error("Failed to resolve field $expr")

                if (belongsToOtherMethod(field, moc)) {
                    moc.method?.capturedFields += field
                    moc.clazz?.capturedFields += field
                    field.isCaptured = true

                    capturedFields.add(field)
                }
            }
        }
    }

    fun belongsToOtherMethod(field: Field, moc: MethodOrClassSpecialization): Boolean {
        val fieldOwner = getFieldOwner(field)
        return fieldOwner != null && fieldOwner != moc.method && fieldOwner != moc.clazz
    }

    fun getFieldOwner(field: Field): Any? {
        var scope = field.ownerScope
        while (true) {
            // if scope-type is lambda, that marks it as a method, kind of, too...
            //  -> make reading lambdas create official methods

            val method = scope.selfAsMethod
            if (method != null) return method

            if (scope.isObjectLike()) return null // no capture necessary
            if (scope.isClassLike()) return scope

            scope = scope.parentIfSameFile
                ?: return null
        }
    }

    override fun getDependencies(key: MethodOrClassSpecialization): Collection<MethodOrClassSpecialization> {
        val method = key.method
        if (method != null) {
            if (method.body == null) return emptyList()
            val dependencies = HashSet<MethodOrClassSpecialization>()
            // todo scan over all child scopes...

            TODO("List methods and classes within 'this'-method")
        }

        val clazz = key.clazz ?: return emptyList()
        for (method in clazz.methods) {

        }
        TODO("List methods within 'this'-class")
    }

    override fun mergeColors(
        key: MethodOrClassSpecialization,
        self: Set<Field>,
        colors: List<Set<Field>>,
        isRecursive: Boolean
    ): Set<Field> {
        // recursive doesn't matter
        return self + colors.flatten()
    }
}