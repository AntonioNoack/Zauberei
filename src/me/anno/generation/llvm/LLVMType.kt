package me.anno.generation.llvm

sealed class LLVMType(val ir: String, val sizeInBytes: Int, val bitMask: Int) {

    object I1 : LLVMType("i1", 1, 1) {
        override fun toString(): String = "LLVM:i1"
    }

    object I8 : LLVMType("i8", 1, 0xff) {
        override fun toString(): String = "LLVM:i8"
    }

    object I16 : LLVMType("i16", 2, 0xffff) {
        override fun toString(): String = "LLVM:i16"
    }

    object I32 : LLVMType("i32", 4, -1) {
        override fun toString(): String = "LLVM:i32"
    }

    object I64 : LLVMType("i64", 8, -1) {
        override fun toString(): String = "LLVM:i64"
    }

    object F32 : LLVMType("float", 4, -1) {
        override fun toString(): String = "LLVM:f32"
    }

    object F64 : LLVMType("double", 8, -1) {
        override fun toString(): String = "LLVM:f64"
    }

    class Ptr(
        val element: LLVMType,
        sizeInBytes: Int,
        val isValueType: Boolean
    ) : LLVMType("${element.ir}*", sizeInBytes, -1) {
        override fun toString(): String {
            return "LLVMPtr:i${sizeInBytes * 8}[$element,${if (isValueType) "value" else "ref"}]"
        }
    }

    class Struct(
        val name: String, val isValueType: Boolean,
        sizeInBytes: Int
    ) : LLVMType(name, sizeInBytes, -1)

}