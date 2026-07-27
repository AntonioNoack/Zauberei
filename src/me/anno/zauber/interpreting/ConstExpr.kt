package me.anno.zauber.interpreting

import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type

object ConstExpr {

    fun evaluateExpression(value: Expression, flags: Int, valueType: Type?): Instance {
        val constValue = evaluateExpressionUnsafe(value, flags, valueType)
        check(constValue.type == ReturnType.RETURN) { "Executing $value returned $constValue" }
        return constValue.value
    }

    private fun findObjectScope(scope: Scope): Scope {
        var scope = scope
        while (true) {
            if (scope.isObjectLike()) return scope
            scope = scope.parent ?: return scope
        }
    }

    fun evaluateExpressionUnsafe(value: Expression, flags: Int, valueType: Type?): BlockReturn {
        val objectScope = findObjectScope(value.scope)
        val tmpMethodScope = objectScope.generate("exprUnsafe", ScopeType.METHOD) // needed? not really...
        tmpMethodScope.setEmptyTypeParams()

        val method = Method(
            null, false, "<exprUnsafe>",
            emptyList(), emptyList(), tmpMethodScope, valueType,
            emptyList(), ReturnExpression(value, null, objectScope, value.origin),
            flags, value.origin
        )
        tmpMethodScope.selfAsMethod = method

        val methodSpec = Specialization(method.scope, emptyParameterList())
        val methodOwnerInstance = runtime.getObjectInstance(objectScope)
        return runtime.executeCall(
            methodOwnerInstance, null,
            methodSpec, emptyList(), value.origin
        )
    }
}