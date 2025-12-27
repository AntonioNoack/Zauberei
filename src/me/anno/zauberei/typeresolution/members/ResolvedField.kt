package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.types.Type

// todo we don't need only the type-param-generics, but also the self-type generics...
class ResolvedField(ownerTypes: List<Type>, field: Field, callTypes: List<Type>, context: ResolutionContext) :
    ResolvedCallable<Field>(ownerTypes, callTypes, field, context) {

    fun getValueType(): Type {
        val field = resolved
        val ownerNames = field.selfTypeTypeParams
        val context = ResolutionContext(
            field.declaredScope, field.selfType,
            false, null /* todo we might need targetType */
        )
        val valueType = field.deductValueType(context)
        val forType = resolveGenerics(valueType, ownerNames, ownerTypes)
        val forCall = resolveGenerics(forType, field.typeParameters, callTypes)
        return forCall
    }

    override fun getTypeFromCall(): Type {
        // this must be a fun-interface, and we need to get the return type of the call...
        //  luckily, there is only a single method, but unfortunately, we need the call parameters...
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        return "ResolvedField(field=$resolved)"
    }
}