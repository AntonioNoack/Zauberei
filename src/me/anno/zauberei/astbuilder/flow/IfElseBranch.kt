package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.UnitType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

class IfElseBranch(val condition: Expression, val ifBranch: Expression, val elseBranch: Expression?) :
    Expression(condition.origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(condition)
        callback(ifBranch)
        if (elseBranch != null) callback(elseBranch)
    }

    override fun resolveType(context: ResolutionContext): Type {
        if (elseBranch == null && !context.allowTypeless)
            throw IllegalStateException("Expected type, but found if without else")
        if (elseBranch == null) return UnitType
        // targetLambdaType stays the same
        val ifType = TypeResolution.resolveType(context, ifBranch)
        val elseType = TypeResolution.resolveType(context, elseBranch)
        return unionTypes(ifType, elseType)
    }
}