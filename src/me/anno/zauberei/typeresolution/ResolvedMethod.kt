package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.typeresolution.ResolvedCallable.Companion.resolveGenerics
import me.anno.zauberei.types.Type

class ResolvedMethod(val method: Method, val generics: List<Type>) : ResolvedCallable {
    override fun getTypeFromCall(): Type {
        return resolveGenerics(method.returnType!!, method.typeParameters, generics)
    }
}