package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.typeresolution.members.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

class ResolvedMethod(
    override val ownerTypes: List<Type>,
    val method: Method,
    override val callTypes: List<Type>
) : ResolvedCallable {

    override fun getTypeFromCall(): Type {
        val ownerNames = (method.selfType as? ClassType)?.clazz?.typeParameters ?: emptyList()
        val forSelf = resolveGenerics(method.returnType!!, ownerNames, ownerTypes)
        val forCall = resolveGenerics(forSelf, method.typeParameters, callTypes)
        return forCall
    }

    override fun toString(): String {
        return "ResolvedMethod(method=$method, generics=$callTypes)"
    }
}