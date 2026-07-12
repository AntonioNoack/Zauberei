package me.anno.generation.llvm

import me.anno.generation.structs.LateinitStruct

class LLVMStruct(
    val superType: LLVMStruct?,
    val typeIndex: Int,
    val typeName: String,
    val isNullable: Boolean
) : LateinitStruct<LLVMProperty>() {

    override fun toString(): String {
        return if (superType != null) {
            "LLVMStruct('$typeName' extends '${superType.typeName}', $properties)"
        } else {
            "LLVMStruct('$typeName', $properties)"
        }
    }
}