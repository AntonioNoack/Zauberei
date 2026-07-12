package me.anno.generation.llvm

import me.anno.zauber.ast.rich.member.Field

data class LLVMProperty(val field: Field?, val llvmType: LLVMType, val index: Int, val offsetInBytes: Int) {
    override fun toString(): String {
        return llvmType.toString()
    }
}