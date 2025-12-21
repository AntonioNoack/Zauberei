package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Field
import me.anno.zauberei.types.Type

class ResolvedField(val field: Field) : ResolvedCallable {
    override fun getTypeFromCall(): Type {
        TODO("Not yet implemented")
    }
}