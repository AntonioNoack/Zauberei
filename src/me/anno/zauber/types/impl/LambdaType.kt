package me.anno.zauber.types.impl

import me.anno.utils.GrowingList
import me.anno.utils.NumberUtils.toInt
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.LambdaParameter
import me.anno.zauber.types.Type

/**
 * Lambda type, with always known parameter types...
 * Self.(A,B,C) -> R
 *
 * todo Int::plus can be understood as (Int,Int) -> Int, and Int.(Int) -> Int, test that both work
 * */
class LambdaType(
    val selfType: Type?,
    val parameters: List<LambdaParameter>,
    val returnType: Type
) : Type() {

    companion object {
        private val lambdaTypeNames = GrowingList { n -> "Function$n" }
        fun getLambdaTypeName(n: Int): String {
            return lambdaTypeNames[n]
        }
    }

    val n get() = (selfType != null).toInt() + parameters.size

    override fun toStringImpl(depth: Int): String {
        val scopeType = if (selfType != null) "self=${selfType.toString(depth)} | " else ""
        return "($scopeType(${
            parameters.joinToString(", ") {
                if (it.name != null) "${it.name}=${it.type.toString(depth)}"
                else it.type.toString(depth)
            }
        }) -> ${returnType.toString(depth)})"
    }

    override fun equals(other: Any?): Boolean {
        return other is LambdaType &&
                parameters == other.parameters &&
                returnType == other.returnType
    }

    fun toScope(): Scope {
        return langScope.getOrPut(getLambdaTypeName(n), ScopeType.INTERFACE)
    }

    fun toClassType( origin: Long): ClassType {
        val base = toScope()[ScopeInitType.AFTER_DISCOVERY]
        val typeParams = ArrayList<Type>(n)
        if (selfType != null) typeParams.add(selfType)
        typeParams.addAll(parameters.map { it.type })
        typeParams.add(returnType)
        return ClassType(base, typeParams, origin)
    }

    override fun resolveImpl(selfScope: Scope?): Type {
        return  LambdaType(selfType?.resolve(selfScope), parameters.map {
            LambdaParameter(it.name, it.type.resolve(selfScope), it.origin)
        }, returnType.resolve(selfScope))
    }

    override fun hashCode(): Int {
        return parameters.hashCode() * 31 + returnType.hashCode()
    }
}