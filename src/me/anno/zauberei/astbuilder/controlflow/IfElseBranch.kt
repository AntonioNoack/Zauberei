package me.anno.zauberei.astbuilder.controlflow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

class IfElseBranch(val condition: Expression, val ifBranch: Expression, val elseBranch: Expression?) :
    Expression(condition.scope, condition.origin) {

    init {
        check(ifBranch.scope != elseBranch?.scope)
        check(
            ifBranch.scope != condition.scope ||
                    ifBranch is SpecialValueExpression
        ) {
            "If and condition somehow have the same scope: ${condition.scope.pathStr}"
        }
        check(
            elseBranch?.scope != condition.scope ||
                    elseBranch is SpecialValueExpression
        ) {
            "Else and condition somehow have the same scope: ${condition.scope.pathStr}"
        }

        ifBranch.scope.addCondition(condition, true)
        elseBranch?.scope?.addCondition(condition, false)
    }

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(condition)
        callback(ifBranch)
        if (elseBranch != null) callback(elseBranch)
    }

    override fun resolveType(context: ResolutionContext): Type {
        if (elseBranch == null) return exprHasNoType(context)
        // targetLambdaType stays the same
        val ifType = TypeResolution.resolveType(context, ifBranch)
        val elseType = TypeResolution.resolveType(context, elseBranch)
        return unionTypes(ifType, elseType)
    }

    override fun clone(scope: Scope): Expression = IfElseBranch(
        condition.clone(scope),
        ifBranch.clone(ifBranch.scope),
        elseBranch?.clone(elseBranch.scope)
    )

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return elseBranch != null && // if else is undefined, this has no return type
                (ifBranch.hasLambdaOrUnknownGenericsType() || elseBranch.hasLambdaOrUnknownGenericsType())
    }

}