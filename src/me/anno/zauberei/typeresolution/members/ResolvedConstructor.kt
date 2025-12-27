package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ResolvedConstructor(ownerTypes: List<Type>, constructor: Constructor, context: ResolutionContext) :
    ResolvedCallable<Constructor>(ownerTypes, emptyList(), constructor, context) {

    override fun getTypeFromCall(): Type {
        return ClassType(resolved.selfType.clazz, ownerTypes)
    }

    override fun toString(): String {
        return "ResolvedConstructor(constructor=$resolved, generics=$ownerTypes)"
    }
}