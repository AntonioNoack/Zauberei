package me.anno.zauber.interpreting

import me.anno.generation.java.JavaSourceGenerator
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type

data class ExternalKey(val ownerScope: Scope, val methodName: String, val valueParameterTypes: List<Type>) {

    fun findMethod(): Method? {
        return ownerScope.methods.firstOrNull { option ->
            option.name == methodName && // todo this search is inefficient
                    option.valueParameters.map { it.type } == valueParameterTypes
        }
    }

    fun findMethodName(sample: JavaSourceGenerator): String? {
        val method = findMethod() ?: return null
        val method0 = Specialization(method.memberScope, emptyParameterList())
        return sample.getMethodName(method0)
    }

    fun str(): String {
        return "$ownerScope.$methodName(${valueParameterTypes.joinToString(", ")})"
    }
}