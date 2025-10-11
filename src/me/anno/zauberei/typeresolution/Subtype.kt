package me.anno.zauberei.typeresolution

/**
 * a instanceof b (subtyping)
 * */
data class Subtype(val a: ResolvedType, val b: ResolvedType, val origin: Int) : Constraint()