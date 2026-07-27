package me.anno.zauber.types

import me.anno.generation.Specializations
import me.anno.utils.ResetThreadLocal
import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.Zauber
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.interpreting.ZClass
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnknownType

// todo we want to define what a specialization actually can contain,
//  e.g. it makes no sense to create 100 variants for wrapper-of-pointer for ArrayList.
//  useful: native types, value types, 'else'
// todo what about Int? (could be optimized after all)
// todo Type-or-null could be mapped to value class Nullable(val value: V, val isNull: Boolean)

class Specialization(val scope: Scope?, typeParameters: ParameterList) {

    constructor(classType: ClassType) :
            this(classType.clazz, classType.createTypeParameterList())

    inline fun <R> use(runnable: () -> R): R {
        val prevSpec = Specializations.specialization
        return try {
            Specializations.specialization = this
            runnable()
        } finally {
            Specializations.specialization = prevSpec
        }
    }

    private val superTypeCache = HashMap<SuperCall, Specialization>()

    val typeParameters = typeParameters.readonly()
    val hash = typeParameters.hashCode() and 0x7fff_ffff

    /**
     * type parameters for all super calls, interfaces, too
     * */
    var implicitTypeParameters = emptyParameterList() // partially required for itself

    init {
        validateCompleteness()
        implicitTypeParameters = collectImplicitTypeParams()
    }

    fun collectImplicitTypeParams(): ParameterList {
        if (scope == null || scope.superCalls.isEmpty()) return emptyParameterList()

        val allParams = scope.superCalls.map { superCall ->
            val p = getSuperType(superCall)
            p.typeParameters + p.implicitTypeParameters
        }.reduce { a, b -> a + b }

        return allParams
    }

    /**
     * check that the specialization contains exactly what we require
     * */
    fun validateCompleteness() {
        if (scope == null) return

        val actualGenerics = typeParameters.generics
        val expectedGenerics = collectGenerics(scope)
        val matchesGenerics = actualGenerics.toSet() == expectedGenerics.toSet()
        if (!matchesGenerics) {
            error("Incomplete generics for $scope: got $typeParameters, expected $expectedGenerics")
        }
    }

    fun isEmpty(): Boolean = typeParameters.isEmpty() && implicitTypeParameters.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()

    fun containsGenerics(): Boolean {
        return typeParameters.any { it is GenericType } ||
                implicitTypeParameters.any { it is GenericType }
    }

    override fun equals(other: Any?): Boolean {
        return other is Specialization &&
                scope == other.scope &&
                typeParameters == other.typeParameters
    }

    operator fun get(type: GenericType): Type? {
        return typeParameters[type] ?: implicitTypeParameters[type]
    }

    operator fun get(type: Parameter): Type? {
        return get(GenericType(type.scope, type.name))
    }

    operator fun plus(other: Specialization): Specialization {
        if (scope == null) return other
        if (other.scope == null) return this
        if (scope == other.scope) return other
        if (scope.isInsideOf(other.scope)) return this
        if (other.scope.isInsideOf(scope)) return other
        return Specialization(null, typeParameters + other.typeParameters)
    }

    fun indexOf(type: Type): Int {
        if (type !is GenericType) return -1
        val i0 = typeParameters.indexOf2(type)
        if (i0 >= 0) return i0
        return implicitTypeParameters.indexOf2(type)
    }

    operator fun contains(type: Type): Boolean {
        return indexOf(type) >= 0
    }

    override fun hashCode(): Int = hash

    fun createUniqueName(): String {

        val name = data.uniqueNames[this]
        if (name != null) return name

        val typeParams = typeParameters
        val genName0 = typeParams.indices.joinToString("_") {
            when (val type = typeParams.getOrNull(it)) {
                is GenericType -> {
                    val selfAsMethod = type.scope.selfAsMethod
                    if (selfAsMethod != null) {
                        "${selfAsMethod.name}_${type.name}"
                    } else {
                        "${type.scope.name}_${type.name}"
                    }
                }
                NullType -> "null"
                null, UnknownType -> "?"
                // todo prefer a short name, so don't use full paths...
                else -> StringStyles.removeStyles(type.toString())
            }
                .replace("(ro)", "")
                .replace(".", "")
                .replace(":", "")
                .replace('<', 'X')
                .replace('>', 'x')
                .replace('(', 'X')
                .replace(')', 'x')
                .replace('[', 'X')
                .replace(']', 'x')
                .replace(", ", "_")
                .replace(",", "_")
                .replace("?", "$")
        }

        if (data.knownNames.add(genName0)) {
            data.uniqueNames[this] = genName0
            return genName0
        }

        for (i in 0 until 1000) {
            val genNameI = "$genName0$i"
            if (data.knownNames.add(genNameI)) {
                data.uniqueNames[this] = genNameI
                return genNameI
            }
        }
        error("Too many duplicates of $genName0")
    }

    override fun toString(): String {
        return "[$scope]" + toStringWithoutScope()
    }

    fun toStringWithoutScope(): String {
        return List(typeParameters.generics.size) { index ->
            IndexedValue(index, typeParameters.generics[index].scope)
        }
            .groupBy { it.value }.entries
            .joinToString(", ", "{", "}") { (key, value) ->
                val indices = value.map { it.index }
                "$key: ${
                    indices.map { index ->
                        val name = typeParameters.generics[index].name
                        val type = typeParameters.getOrNull(index)
                        "${style(name, GREEN)}=$type"
                    }
                }"
            }
    }

    fun withScope(scope: Scope): Specialization {
        return if (this.scope == scope) this
        else {
            val expected = collectGenerics(scope)
            Specialization(scope, typeParameters.filterByGenerics { it in expected })
        }
    }

    fun withScopeUnknownIfMissing(scope: Scope): Specialization {
        return if (this.scope == scope) this
        else {
            val expected = collectGenerics(scope)
            Specialization(scope, typeParameters.filterByGenericsUnknownIfMissing(expected))
        }
    }

    val superType: Specialization?
        get() {

            val clazz = scope!!
            check(clazz.isClassLike())

            if (clazz.isPackage()) {
                return fromSimple(Types.Any.clazz)
            }

            val superCall = clazz[ScopeInitType.AFTER_DISCOVERY]
                .superCalls.firstOrNull { superCall -> superCall.isClassCall }
                ?: return null

            return getSuperType(superCall)
        }

    fun getSuperType(superCall: SuperCall): Specialization {
        return superTypeCache.getOrPut(superCall) {
            getSuperTypeImpl(superCall)
        }
    }

    private fun getSuperTypeImpl(superCall: SuperCall): Specialization {
        val clazz = scope!!
        check(clazz.isClassLike())

        if (clazz.isPackage()) {
            return fromSimple(Types.Any.clazz)
        }

        val superScope = superCall.type.clazz

        // todo we must also check const value-params
        val generics = superScope.typeParameters
        if (generics.isEmpty() && !superScope.isInnerClass()) {
            return fromSimple(superScope)
        }

        // todo we must also check const value-params
        val typeParams = superCall.type.typeParameters ?: emptyList()
        val superTypeParams = typeParams.map { type -> type.specialize(this) }
        return Specialization(superScope, ParameterList(generics, superTypeParams))
    }

    val clazz: Scope
        get() {
            check(scope != null)
            check(scope.scopeType != null) { "Unknown scope $scope" }
            check(scope.isClassLike()) { "$scope is not class-like: ${scope.scopeType}" }
            return scope
        }

    val method: MethodLike
        get() {
            check(scope != null)
            check(scope.isMethodLike()) {
                "Expected $scope to be method-like, got ${scope.scopeType}"
            }
            return scope.selfAsMethod
                ?: scope.selfAsConstructor
                ?: error("$scope[${scope.scopeType}] is method-like, but has no method?")
        }

    val field: Field
        get() {
            check(scope != null)
            check(scope.scopeType == ScopeType.FIELD)
            return scope.selfAsField!!
        }

    fun isClassLike() = scope != null && scope.isClassLike()
    fun isMethodLike() = scope != null && scope.isMethodLike()

    companion object {

        class Data {
            val uniqueNames = HashMap<Specialization, String>()
            val knownNames = HashSet<String>()
        }

        private val data by ResetThreadLocal.threadLocal { Data() }
        private val cache by ResetThreadLocal.threadLocal { HashMap<Scope, Specialization>() }

        fun fromSimple(scope: Scope): Specialization {
            check(scope.declaredTypeParameters.isEmpty())
            check(scope.isClassLike() || scope.isMethodLike())
            return cache.getOrPut(scope) {
                Specialization(scope, emptyParameterList())
            }
        }

        fun collectGenerics(scope: Scope): List<Parameter> {
            var scope = scope
            val scopes = ArrayList<Scope>()
            val result = ArrayList<Parameter>()
            while (true) {
                scopes.add(scope)

                if (scope.isObjectLike()) break
                if (scope.isClass() &&
                    scope.scopeType != ScopeType.INNER_CLASS &&
                    scope.scopeType != ScopeType.INLINE_CLASS
                ) break

                scope = scope.parentIfSameFile ?: break
            }
            for (scopeI in scopes.asReversed()) {
                result.addAll(scopeI.declaredTypeParameters)
                if (scopeI.isClass()) {
                    val constr = scopeI.getOrCreatePrimaryConstructorScope()
                        .selfAsConstructor!!
                    for (param in constr.valueParameters) {
                        if (param.isConst) {
                            result.add(param)
                        }
                    }
                }
            }
            return result
        }

        fun filterSpecialization(type: Type, generic: Parameter): Type {
            if (generic.isVal) return type
            return when (type) {
                in ZClass.nativeTypes -> type
                is ClassType if type.clazz.isValueType() -> type
                else -> generic.type
            }
        }

        fun allUnknown(scope: Scope): Specialization {
            val generics = collectGenerics(scope)
            val parameters = ParameterList(generics, generics.map { it.type })
            return Specialization(scope, parameters)
        }

        fun withScopeUnknownIfMissing(scope: Scope, typeParameters: ParameterList): Specialization {
            val expected = collectGenerics(scope)
            return Specialization(scope, typeParameters.filterByGenericsUnknownIfMissing(expected))
        }

        val noSpecialization by ResetThreadLocal.threadLocal {
            Specialization(Zauber.root, emptyParameterList())
        }

        fun ClassType.createTypeParameterList(): ParameterList {
            val generics = clazz.typeParameters
            val provided = (typeParameters ?: emptyList()).ifEmpty { generics.map { it.type } }
            assertEquals(generics.size, provided.size) {
                "Generics-size mismatch: $generics vs $this"
            }
            return ParameterList(generics, provided)
        }
    }
}
