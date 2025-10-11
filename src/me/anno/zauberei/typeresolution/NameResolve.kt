package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.types.Scope

/**
 * name in a scope (unqualified)
 * */
data class NameResolve(
    val scope: Scope, // starting scope
    val name: String,
    val kind: NameKind, // TYPE | FUNCTION | FIELD (or ANY)
    val result: ResolvedType?,
    val origin: Expression?
) : Constraint()