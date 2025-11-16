package me.anno.zauberei.typeresolution.graph

/**
 * a instanceof b (subtyping)
 * */
data class SubtypeConstraint(val parentType: ResolvingType, val subType: ResolvingType, val origin: Int)