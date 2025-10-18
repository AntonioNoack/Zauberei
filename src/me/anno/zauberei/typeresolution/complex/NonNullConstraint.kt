package me.anno.zauberei.typeresolution.complex

/**
 * nonNullType = nullableType!!
 * */
data class NonNullConstraint(val nullableType: ResolvingType, val nonNullType: ResolvingType, val origin: Int)