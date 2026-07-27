package me.anno.generation

import me.anno.compilation.MinimalRustCompiler
import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.rust.RustSourceGenerator.Companion.nativeRustNumbers
import me.anno.zauber.typeresolution.TypeResolution
import org.junit.jupiter.api.Test

/**
 * execution time: 1.3s with preserveFolder=true instead of ~10s
 * */
class RustGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        for ((type, _) in nativeRustNumbers) {
            JavaSourceGenerator.register(
                TypeResolution.langScope, "println", listOf(type),
                "println!(\"{}\", arg0)"
            )
        }
    }

    override fun generator() = MinimalRustCompiler(true)

    @Test
    fun testOperationOrder() {
        testOperationOrderImpl()
    }

    @Test
    fun testPrintFloats() {
        testPrintFloatsImpl()
    }

    @Test
    fun testMethodCall() {
        testMethodCallImpl()
    }

    @Test
    fun testDataClassAndAllocation() {
        testDataClassAndAllocationImpl()
    }

    @Test
    fun testSuperAndSelfConstructorBeingCalled() {
        testSuperAndSelfConstructorBeingCalledImpl()
    }

    @Test
    fun testGenericClass() {
        testGenericClassImpl()
    }

    @Test
    fun testValueClassFieldIsWritable() {
        testValueClassFieldIsWritableImpl()
    }

    @Test
    fun testValueIsPassedByCopy() {
        testValueIsPassedByCopyImpl()
    }

    @Test
    fun testSimpleBranch() {
        testSimpleBranchImpl()
    }

    @Test
    fun testSimpleLoop() {
        testSimpleLoopImpl()
    }

    @Test
    fun testIntArray() {
        testIntArrayImpl()
    }

    @Test
    fun testReferenceArray() {
        testReferenceArrayImpl()
    }

    @Test
    fun testClassInheritance() {
        testClassInheritanceImpl()
    }

    @Test
    fun testInterfaceInheritance() {
        testInterfaceInheritanceImpl()
    }

    @Test
    fun testNumberOverflows() {
        testNumberOverflowsImpl()
    }

    @Test
    fun testNumberNegation() {
        testNumberNegationImpl()
    }

    @Test
    fun testBinaryNumberOperations() {
        testBinaryNumberOperationsImpl()
    }

    @Test
    fun testNumberComparisons() {
        testNumberComparisonsImpl()
    }

    @Test
    fun testNumberConversions() {
        testNumberConversionsImpl()
    }

    @Test
    fun testNonNumberComparisons() {
        testNonNumberComparisonsImpl()
    }

    @Test
    fun testLogicalOperators() {
        testLogicalOperatorsImpl()
    }

    @Test
    fun testInstanceOf() {
        testInstanceOfImpl()
    }

    @Test
    fun testImplicitConversion() {
        testImplicitConversionImpl()
    }

    @Test
    fun testStringOps() {
        testStringOpsImpl()
    }

    @Test
    fun testUseNativeLibrary() {
        TODO("Call into a native library")
    }

}