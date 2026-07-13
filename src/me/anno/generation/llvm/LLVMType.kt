package me.anno.generation.llvm

sealed class LLVMType(val ir: String, val sizeInBytes: Int, val bitMask: Int) {

    object I1 : LLVMType("i1", 1, 1)
    object I8 : LLVMType("i8", 1, 0xff)
    object I16 : LLVMType("i16", 2, 0xffff)
    object I32 : LLVMType("i32", 4, -1)
    object I64 : LLVMType("i64", 8, -1)
    object F32 : LLVMType("float", 4, -1)
    object F64 : LLVMType("double", 8, -1)

    class Ptr(
        val element: LLVMType,
        val isValueType: Boolean
    ) : LLVMType("${element.ir}*", 8 /* todo only 4 in 32-bit mode, e.g. on GPU */, -1)

    class Struct(
        val name: String, val isValueType: Boolean,
        sizeInBytes: Int
    ) : LLVMType(name, sizeInBytes, -1)

}