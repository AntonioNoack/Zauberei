package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.typeresolution.members.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.Type

class ResolvedField(
    // todo we don't need only the type-param-generics, but also the self-type generics...
    override val ownerTypes: List<Type>,
    val field: Field,
    override val callTypes: List<Type>
) : ResolvedCallable {

    fun getValueType(): Type {
        val ownerNames = field.selfTypeTypeParams
        println("ownerTypes: $ownerTypes")
        val forType = resolveGenerics( field.valueType!!, ownerNames, ownerTypes)
        val forCall = resolveGenerics(forType, field.typeParameters, callTypes)
        return forCall
    }

    override fun getTypeFromCall(): Type {
        // this must be a fun-interface, and we need to get the return type of the call...
        //  luckily, there is only a single method, but unfortunately, we need the call parameters...
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        return "ResolvedField(field=$field)"
    }
}