package me.anno.zauberei.types.impl

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.NullableAnyType

/**
 * Generic type, named 'name', defined in 'scope';
 * look at scope to find the type
 * */
class GenericType(val scope: Scope, val name: String) : Type() {

    val byTypeParameter: Parameter
        get() = scope.typeParameters.firstOrNull { it.name == name /*&& it.scope == scope -> automatically filtered for */ }
            ?: throw IllegalStateException("Missing generic parameter '$name' in ${scope.pathStr}")

    val superBounds: Type
        get() = byTypeParameter.type

    override fun toString(): String {
        return if(superBounds == NullableAnyType) {
            "${scope.pathStr}.$name"
        } else {
            "(${scope.pathStr}.$name: $superBounds)"
        }
    }
}