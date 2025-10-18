package me.anno.zauberei.typeresolution.complex

/**
 * a instanceof b (subtyping)
 * */
data class SubtypeConstraint(val parentType: ResolvingType, val subType: ResolvingType, val origin: Int)