package me.anno.zauberei.typeresolution

/**
 * nonNullType = nullableType!!
 * */
data class NonNullConstraint(val nullableType: ResolvingType, val nonNullType: ResolvingType, val origin: Int) : Constraint()