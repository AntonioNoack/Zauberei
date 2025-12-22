package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.expression.constants.SpecialValue
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.types.Scope

private fun compareTo(left: Expression, right: Expression) =
    NamedCallExpression(
        left, "compareTo", emptyList(),
        listOf(NamedParameter(null, right)), right.scope, right.origin
    )

@Suppress("IntroduceWhenSubject") // this feature is experimental, why is it recommended???
fun ASTBuilder.binaryOp(
    scope: Scope, left: Expression, symbol: String, right: Expression,
    origin: Int = left.origin
): Expression {
    return when (symbol) {
        "<=" -> CompareOp(compareTo(left, right), CompareType.LESS_EQUALS)
        "<" -> CompareOp(compareTo(left, right), CompareType.LESS)
        ">=" -> CompareOp(compareTo(left, right), CompareType.GREATER_EQUALS)
        ">" -> CompareOp(compareTo(left, right), CompareType.GREATER)
        "==" -> CheckEqualsOp(left, right, byPointer = false, negated = false, origin)
        "!=" -> CheckEqualsOp(left, right, byPointer = false, negated = true, origin)
        "===" -> CheckEqualsOp(left, right, byPointer = true, negated = false, origin)
        "!==" -> CheckEqualsOp(left, right, byPointer = true, negated = true, origin)
        "::" -> {
            fun getBase(): Scope = when {
                // left is VariableExpression -> scope.resolveType(left.name, this) as Scope
                left is SpecialValueExpression && left.value == SpecialValue.THIS -> scope
                else -> throw NotImplementedError("GetBase($left::$right at ${tokens.err(i)})")
            }

            val leftIsType = left is VariableExpression && left.name[0].isUpperCase() ||
                    left is SpecialValueExpression && left.value == SpecialValue.THIS
            when {
                leftIsType && right is SpecialValueExpression && right.value == SpecialValue.CLASS -> {
                    GetClassFromTypeExpression(getBase(), left.scope, left.origin)
                }
                right is SpecialValueExpression && right.value == SpecialValue.CLASS -> {
                    GetClassFromValueExpression(left, right.origin)
                }
                leftIsType && right is VariableExpression -> {
                    GetMethodFromTypeExpression(getBase(), right.name, right.scope, right.origin)
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
                AssignIfMutableExpr(left, symbol, right)
            } else if (symbol.startsWith("!")) {
                val methodName = lookupBinaryOp(symbol.substring(1))
                val param = NamedParameter(null, right)
                val base = NamedCallExpression(
                    left, methodName, emptyList(), listOf(param),
                    right.scope, right.origin
                )
                PrefixExpression(PrefixType.NOT, right.origin, base)
            } else if (symbol == "." && right is NamedCallExpression) {
                // todo ideally, this would be handled by association-order...
                // reorder stack from left to right
                val leftAndMiddle = NamedCallExpression(
                    left, ".", emptyList(),
                    listOf(NamedParameter(null, right.base)),
                    left.scope, left.origin
                )
                NamedCallExpression(
                    leftAndMiddle, right.name,
                    right.typeParameters, right.valueParameters,
                    right.scope, right.origin
                )
            } else {
                val methodName = lookupBinaryOp(symbol)
                val param = NamedParameter(null, right)
                NamedCallExpression(
                    left, methodName, emptyList(), listOf(param),
                    right.scope, right.origin
                )
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