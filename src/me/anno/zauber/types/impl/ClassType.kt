package me.anno.zauber.types.impl

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.InsertMode
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import java.util.*

/**
 * A scope, but also with optional type arguments,
 * e.g. ArrayList, ArrayList<Int> or Map<Key, Value>
 *
 * todo typeParameters shall contain outer-values for inner classes, too
 * */
class ClassType(val clazz: Scope, typeParameters0: ParameterList?) : Type() {

    companion object {

        private val LOGGER = LogManager.getLogger(ClassType::class)

        /**
         * yes for compiling,
         * no for language server
         * */
        var strictMode = true

        fun createParameterList(
            clazz: Scope,
            typeParams: List<Type>?,
            origin: Long,
            ignoreSIT: Boolean = false
        ): ParameterList {

            if (typeParams == null) {
                return unknownParameterList(clazz)
            }

            val generics = clazz.typeParameters
            if (strictMode) {
                if (!ignoreSIT) clazz[ScopeInitType.AFTER_DISCOVERY]
                if (!clazz.hasTypeParameters && (clazz.scopeType == null || clazz.scopeType == ScopeType.PACKAGE)) {
                    LOGGER.warn("Scope $clazz wasn't defined explicitly, assigning package type")
                    clazz.scopeType = ScopeType.PACKAGE
                }
                check(clazz.hasTypeParameters) {
                    "$clazz is missing type parameter definition, at ${resolveOrigin(origin)}"
                }
                check(generics.size == typeParams.size) {
                    "Incorrect number of typeParams for $clazz, " +
                            "expected ${generics.size}, " +
                            "got ${typeParams.size}, " +
                            "at ${resolveOrigin(origin)}, " +
                            "defined in ${clazz.fileName}"
                }
            }
            if (generics.isEmpty()) return emptyParameterList()
            val result = ParameterList(generics)
            for (i in typeParams.indices) {
                result.set(i, typeParams[i], InsertMode.READ_ONLY)
            }
            return result
        }

        private fun unknownParameterList(clazz: Scope): ParameterList {
            val generics = clazz.typeParameters
            if (generics.isEmpty()) return emptyParameterList()

            val result = ParameterList(generics)
            for (i in generics.indices) {
                val generic = generics[i]
                result.set(i, GenericType(generic.scope, generic.name), InsertMode.READ_ONLY)
            }
            return result
        }
    }

    constructor(clazz: Scope, typeParameters: List<Type>?, origin: Long) :
            this(clazz, createParameterList(clazz, typeParameters, origin))

    constructor(clazz: Scope, typeParameters: List<Type>?, origin: Long, ignoreSIT: Boolean) :
            this(clazz, createParameterList(clazz, typeParameters, origin, ignoreSIT))

    init {
        check(clazz.scopeType != ScopeType.ENUM_ENTRY) {
            "Classes should use the general enum, not the entries, violation: $clazz"
        }
    }

    val typeParameters: ParameterList? = typeParameters0

    fun withTypeParameters(typeParameters: List<Type>): ClassType {
        val clazz = clazz[ScopeInitType.AFTER_DISCOVERY]
        check(clazz.hasTypeParameters) { "Class $clazz is missing type parameters" }
        check(clazz.typeParameters.size == typeParameters.size) {
            "Cannot create ClassType($clazz, |${clazz.typeParameters}| != |$typeParameters|)"
        }
        return ClassType(clazz, ParameterList(clazz.typeParameters, typeParameters))
    }

    fun withTypeParameters(vararg typeParameters: Type): ClassType {
        return withTypeParameters(typeParameters.asList())
    }

    fun withTypeParameters(typeParameters: ParameterList?): ClassType {
        if (typeParameters == this.typeParameters) return this
        return ClassType(clazz, typeParameters)
    }

    fun withTypeParameter(typeParameter: Type): ClassType {
        return ClassType(clazz, ParameterList(clazz.typeParameters, listOf(typeParameter)))
    }

    override fun equals(other: Any?): Boolean {
        return other is ClassType &&
                clazz == other.clazz &&
                (classHasNoTypeParams() || (typeParameters == other.typeParameters))
    }

    override fun hashCode(): Int {
        return clazz.pathStr.hashCode()
    }

    fun classHasNoTypeParams(): Boolean {
        return clazz.typeParameters.isEmpty()
    }

    override fun toStringImpl(depth: Int): String {
        val typeParameters = typeParameters
            ?: return "$clazz<?>"

        if (typeParameters.isEmpty()) return clazz.toString()
        if (depth <= 0) return "$clazz..."

        if (!clazz.isInnerClass()) {
            return clazz.toString() + typeParameters.toString("<", ">", depth)
        }

        return toStringImplForInnerClasses(depth)
    }

    override fun specialize(spec: Specialization): ClassType {
        check(clazz[ScopeInitType.AFTER_DISCOVERY].hasTypeParameters) { "Class $clazz is missing type parameters" }
        val typeParameters = typeParameters ?: ParameterList(clazz.typeParameters)
        return ClassType(clazz, typeParameters.map { param -> param.specialize(spec) })
    }

    private fun toStringImplForInnerClasses(depth: Int): String {
        val builder = StringBuilder()
        val scopes = ArrayDeque<Scope>()
        var scope: Scope? = clazz
        while (scope != null) {
            scopes.addFirst(scope)
            if (!scope.isInnerClass()) {
                builder.append(scope.parent!!.pathStr)
                break
            }
            scope = scope.parentIfSameFile
                ?: error("Scope $scope must have parent")
        }

        var index = 0
        for (current in scopes) {
            if (builder.isNotEmpty()) builder.append('.')
            builder.append(current.name)
            val count = current.declaredTypeParameters.size
            if (count > 0) {
                val currentTypeParameters = typeParameters!!.subList(index, index + count)
                builder.append(currentTypeParameters.joinToString(", ", "<", ">") { it.toString(depth) })
                index += count
            }
        }
        return builder.toString()
    }
}
