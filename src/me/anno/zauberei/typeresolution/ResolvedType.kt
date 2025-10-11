package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type

/**
 * Marker for a type which may be unknown (TypeVar) or concrete
 * */
sealed class ResolvedType {
    data class Concrete(val t: Type) : ResolvedType()
    data class Var(val id: Int) : ResolvedType()
}