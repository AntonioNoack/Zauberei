package me.anno.zauberei.typeresolution

/**
 * unify a and b (a == b)
 * */
data class Equality(val a: ResolvingType, val b: ResolvingType, val origin: Int) : Constraint()