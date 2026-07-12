package me.anno.generation.llvm

import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.java.JavaSourceGenerator.Companion.resolveType
import me.anno.generation.structs.IntPair
import me.anno.generation.structs.Structures
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isValue
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

class LLVMStructures(val gen: JavaSourceGenerator) : Structures<LLVMType, LLVMProperty, LLVMStruct>() {

    override fun getInnerType(type: Type): LLVMType {
        return when (val type = resolveType(type)) {

            Types.Boolean -> LLVMType.I1

            Types.Byte, Types.UByte -> LLVMType.I8
            Types.Short, Types.UShort, Types.Char -> LLVMType.I16
            Types.Int, Types.UInt -> LLVMType.I32

            Types.Long, Types.ULong -> LLVMType.I64

            Types.Float, Types.Half -> LLVMType.F32
            Types.Double -> LLVMType.F64

            is ClassType -> {
                val struct0 = getStruct(Specialization(type))
                val structName = struct0.typeName
                val isValue = type.isValue()
                val struct = LLVMType.Struct(structName, isValue, struct0.sizeInBytes)
                LLVMType.Ptr(struct, isValue)
            }
            else -> getInnerType(Types.Any) // fallback
        }
    }

    override fun createProperty(field: Field?, type: LLVMType, index: Int, offset: Int): LLVMProperty =
        LLVMProperty(field, type, index, offset)

    override fun createArrayContentProperty(
        elementLLVMType: LLVMType,
        isValue: Boolean, index: Int, offset: Int,
    ): LLVMProperty {
        return LLVMProperty(
            null,
            LLVMType.Ptr(elementLLVMType, isValue),
            index, offset
        )
    }

    override fun createStruct(
        superType: LLVMStruct?, typeIndex: Int,
        typeName: String, isNullable: Boolean
    ): LLVMStruct = LLVMStruct(superType, typeIndex, typeName, isNullable)

    override fun getClassName(clazz: Scope, specialization: Specialization): String =
        "%" + gen.getClassName(clazz, specialization)

    override fun isStoredField(field: Field): Boolean = gen.isStoredField(field)

    override fun getElementSizeAndAlignment(type: LLVMType): IntPair {
        return when (type) {
            LLVMType.I1, LLVMType.I8 -> IntPair(1, 1)
            LLVMType.I16 -> IntPair(2, 2)
            LLVMType.I32, LLVMType.F32 -> IntPair(4, 4)
            else -> if (type is LLVMType.Struct && type.isValueType) {
                IntPair(type.sizeInBytes, 8)
            } else IntPair(8, 8)
        }
    }

}