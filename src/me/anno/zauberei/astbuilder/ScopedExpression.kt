package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope

data class ScopedExpression(val scope: Scope, val expression: Expression)