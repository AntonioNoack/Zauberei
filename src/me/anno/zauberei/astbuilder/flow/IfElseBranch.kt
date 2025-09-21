package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class IfElseBranch(val condition: Expression, val ifTrue: Expression, val ifFalse: Expression): Expression()