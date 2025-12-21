package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ResolvedConstructor(val constructor: Constructor, val generics: List<Type>) : ResolvedCallable {
    override fun getTypeFromCall(): Type {
        return if (generics.isEmpty()) constructor.clazz.typeWithoutArgs
        else ClassType(constructor.clazz, generics)
    }
}