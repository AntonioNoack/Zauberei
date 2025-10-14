package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.expression.constants.ConstantExpression
import me.anno.zauberei.types.Scope

private fun compareTo(left: Expression, right: Expression) =
    NamedCallExpression(left, "compareTo", emptyList(), listOf(right), right.origin)

@Suppress("IntroduceWhenSubject") // this feature is experimental, why is it recommended???
fun ASTBuilder.binaryOp(scope: Scope, left: Expression, symbol: String, right: Expression): Expression {
    return when (symbol) {
        "<=" -> CompareOp(compareTo(left, right), CompareType.LESS_EQUALS)
        "<" -> CompareOp(compareTo(left, right), CompareType.LESS)
        ">=" -> CompareOp(compareTo(left, right), CompareType.GREATER_EQUALS)
        ">" -> CompareOp(compareTo(left, right), CompareType.GREATER)
        "==" -> CheckEqualsOp(left, right, byPointer = false, negated = false)
        "!=" -> CheckEqualsOp(left, right, byPointer = false, negated = true)
        "===" -> CheckEqualsOp(left, right, byPointer = true, negated = false)
        "!==" -> CheckEqualsOp(left, right, byPointer = true, negated = true)
        "::" -> {
            fun getBase(): Scope = when {
                left is VariableExpression -> scope.resolveType(left.name, this) as Scope
                left is ConstantExpression && left.value == ConstantExpression.Constant.THIS -> scope
                else -> throw NotImplementedError("GetBase($left::$right at ${tokens.err(i)})")
            }

            val leftIsType = left is VariableExpression && left.name[0].isUpperCase() ||
                    left is ConstantExpression && left.value == ConstantExpression.Constant.THIS
            when {
                leftIsType && right is ConstantExpression && right.value == ConstantExpression.Constant.CLASS -> {
                    GetClassFromTypeExpression(getBase(), left.origin)
                }
                right is ConstantExpression && right.value == ConstantExpression.Constant.CLASS -> {
                    GetClassFromValueExpression(left, right.origin)
                }
                leftIsType && right is VariableExpression -> {
                    GetMethodFromTypeExpression(getBase(), right.name, right.origin)
                }
                right is VariableExpression -> {
                    GetMethodFromValueExpression(left, right.name, right.origin)
                }
                else -> throw NotImplementedError("WhichType? $left::$right")
            }
        }
        "=" -> AssignmentExpression(left, right)
        else -> {
            if (symbol.endsWith('=')) {
                // todo oh no, to know whether this is mutable or not,
                //  we have to know all types, because left may be really complicated,
                //  e.g. a["5",3].x()() += 17
                return AssignIfMutableExpr(left, symbol, right)
            } else if (symbol.startsWith("!")) {
                val methodName = lookupBinaryOp(symbol.substring(1))
                val base = NamedCallExpression(left, methodName, emptyList(), listOf(right), right.origin)
                return PrefixExpression(PrefixType.NOT, right.origin, base)
            } else {
                val methodName = lookupBinaryOp(symbol)
                return NamedCallExpression(left, methodName, emptyList(), listOf(right), right.origin)
            }
        }
    }
}

fun lookupBinaryOp(symbol: String): String {
    return when (symbol) {
        "+" -> "plus"
        "-" -> "minus"
        "*" -> "times"
        "/" -> "div"
        "%" -> "rem"
        ".." -> "rangeTo"
        "..<" -> "rangeUntil"
        "in" -> "contains"
        "&&" -> "shortcutAnd"
        "||" -> "shortcutOr"
        ".", ".?", "?:" -> symbol
        else -> {
            println("unknown binary op: $symbol")
            symbol
        }
    }
}