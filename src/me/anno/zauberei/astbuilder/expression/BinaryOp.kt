package me.anno.zauberei.astbuilder.expression

import com.sun.org.apache.xalan.internal.xsltc.compiler.sym

private fun compareTo(left: Expression, right: Expression) =
    NamedCallExpression(left, "compareTo", emptyList(), listOf(right))

fun BinaryOp(left: Expression, symbol: String, right: Expression): Expression {
    return when (symbol) {
        "<=" -> IntCompareZeroOp(compareTo(left, right), CompareType.LESS_EQUALS)
        "<" -> IntCompareZeroOp(compareTo(left, right), CompareType.LESS)
        ">=" -> IntCompareZeroOp(compareTo(left, right), CompareType.GREATER_EQUALS)
        ">" -> IntCompareZeroOp(compareTo(left, right), CompareType.GREATER)
        "==" -> CheckEqualsOp(left, right, byPointer = false, negated = false)
        "!=" -> CheckEqualsOp(left, right, byPointer = false, negated = true)
        "===" -> CheckEqualsOp(left, right, byPointer = true, negated = false)
        "!==" -> CheckEqualsOp(left, right, byPointer = true, negated = true)
        else -> {
            if (symbol.endsWith('=')) {
                // todo oh no, to know whether this is mutable or not,
                //  we have to know all types, because left may be really complicated,
                //  e.g. a["5",3].x()() += 17
                return AssignIfMutableExpr(left, symbol, right)
            } else if (symbol.startsWith("!")) {
                val methodName = lookupBinaryOp(symbol.substring(1))
                val base = NamedCallExpression(left, methodName, emptyList(), listOf(right))
                return PrefixExpression("!", base)
            } else {
                val methodName = lookupBinaryOp(symbol)
                return NamedCallExpression(left, methodName, emptyList(), listOf(right))
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
        else -> {
            println("unknown binary op: $symbol")
            symbol
        }
    }
}