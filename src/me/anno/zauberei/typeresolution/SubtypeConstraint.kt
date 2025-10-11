package me.anno.zauberei.typeresolution

/**
 * a instanceof b (subtyping)
 * */
data class SubtypeConstraint(val parentType: ResolvingType, val subType: ResolvingType, val origin: Int) : Constraint()