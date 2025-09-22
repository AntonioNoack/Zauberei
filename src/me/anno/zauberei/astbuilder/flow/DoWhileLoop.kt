package me.anno.zauberei.astbuilder.flow

import me.anno.zauberei.astbuilder.expression.Expression

class DoWhileLoop(val body: Expression, val condition: Expression, val label: String?) : Expression()