package me.anno.zauberei.types

/**
 * Resolved generic type;
 * look at scope to find the type
 * */
class GenericType(val scope: Scope, val name: String) : Type() {

    val param
        get() = scope.typeParameters.first { it.name == name }

    val superBounds: Type
        get() = param.type

    override fun toString(): String {
        return "GenericType(${scope.pathStr},'$name':$superBounds)"
    }
}