package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ResolvedMethod(ownerTypes: List<Type>, method: Method, callTypes: List<Type>, context: ResolutionContext) :
    ResolvedCallable<Method>(ownerTypes, callTypes, method, context) {

    override fun getTypeFromCall(): Type {
        val method = resolved
        val ownerNames = (method.selfType as? ClassType)?.clazz?.typeParameters ?: emptyList()
        val forSelf = resolveGenerics(method.returnType!!, ownerNames, ownerTypes)
        val forCall = resolveGenerics(forSelf, method.typeParameters, callTypes)
        return forCall
    }

    override fun toString(): String {
        return "ResolvedMethod(method=$resolved, generics=$callTypes)"
    }
}