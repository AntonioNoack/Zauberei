package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ResolvedConstructor(
    override val ownerTypes: List<Type>,
    val constructor: Constructor
) : ResolvedCallable {

    override val callTypes: List<Type>
        get() = emptyList() // cannot be defined (yet?)

    override fun getTypeFromCall(): Type {
        return ClassType(constructor.selfType.clazz, ownerTypes)
    }

    override fun toString(): String {
        return "ResolvedConstructor(constructor=$constructor, generics=$ownerTypes)"
    }
}