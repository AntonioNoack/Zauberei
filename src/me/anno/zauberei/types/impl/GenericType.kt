package me.anno.zauberei.types.impl

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

/**
 * Generic type, named 'name', defined in 'scope';
 * look at scope to find the type
 * */
class GenericType(val scope: Scope, val name: String) : Type() {

    val byTypeParameter: Parameter
        get() = scope.typeParameters.first { it.name == name }

    val superBounds: Type
        get() = byTypeParameter.type

    override fun toString(): String {
        return "GenericType(${scope.pathStr},'$name':$superBounds)"
    }
}