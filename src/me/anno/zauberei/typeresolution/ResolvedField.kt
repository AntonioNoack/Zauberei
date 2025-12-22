package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.types.Type

class ResolvedField(val field: Field) : ResolvedCallable {
    override fun getTypeFromCall(): Type {
        // this must be a fun-interface, and we need to get the return type of the call...
        //  luckily, there is only a single method, but unfortunately, we need the call parameters...
        TODO("Not yet implemented")
    }
}