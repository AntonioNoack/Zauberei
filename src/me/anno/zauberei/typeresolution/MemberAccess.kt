package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.expression.Expression

/**
 * Resolve member name on base type; produce candidates (deferred)
 * */
data class MemberAccess(
    val base: ResolvedType,
    val name: String,
    val isCall: Boolean,
    val typeParams: List<ResolvedType>,
    val params: List<ResolvedType>,
    val result: ResolvedType, // result will be filled/linked to chosen candidate's return type
    val origin: Expression
) : Constraint()