package me.anno.zauberei.typeresolution

/**
 * unify a and b (a == b)
 * */
data class Equality(val a: ResolvedType, val b: ResolvedType, val origin: Int) : Constraint()