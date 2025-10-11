package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.Compile.root
import me.anno.zauberei.typeresolution.ResolvingType
import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Type

abstract class Expression(val origin: Int) {

    var resolvedType: Type? = null
    var resolvedTypeI: ResolvingType? = null

    fun getOrFindType(findType: (Expression) -> Type): Type {
        val resolvedType = resolvedType
        if (resolvedType != null) {
            if (resolvedType == InvalidType) throw IllegalStateException("Recursive dependency on $this")
            return resolvedType
        }
        this.resolvedType = InvalidType
        val found = findType(this)
        this.resolvedType = found
        return found
    }

    abstract fun forEachExpr(callback: (Expression) -> Unit)

    companion object {
        private val InvalidType = ClassType(root, emptyList())
    }
}