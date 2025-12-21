package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.LambdaParameter
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.*
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

interface ResolvedCallable {

    fun getTypeFromCall(): Type

    companion object {
        fun resolveGenerics(
            type: Type,
            genericNames: List<Parameter>,
            genericValues: List<Type>
        ): Type {
            if (genericValues.isEmpty()) return type
            return when (type) {
                is GenericType -> {
                    val idx = genericNames.indexOfFirst { it.name == type.name }
                    if (idx >= 0) genericValues[idx] else type
                }
                is UnionType -> {
                    type.types.map { partType ->
                        resolveGenerics(partType, genericNames, genericValues)
                    }.reduce { a, b -> unionTypes(a, b) }
                }
                is ClassType -> {
                    val typeArgs = type.typeArgs ?: return type
                    val newTypeArgs = typeArgs.map { partType ->
                        resolveGenerics(partType, genericNames, genericValues)
                    }
                    ClassType(type.clazz, newTypeArgs)
                }
                NullType -> type
                is LambdaType -> {
                    LambdaType(type.parameters.map {
                        val newType = resolveGenerics(it.type, genericNames, genericValues)
                        LambdaParameter(it.name, newType)
                    }, resolveGenerics(type.returnType, genericNames, genericValues))
                }
                else -> throw NotImplementedError("Resolve generics in $type")
            }
        }
    }
}