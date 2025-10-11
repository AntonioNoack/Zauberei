package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type

/**
 * For generic param bounds: var <: bound, bound <: var etc.
 * */
data class Bound(val variable: ResolvingType, val bound: Type, val origin: Int) : Constraint()