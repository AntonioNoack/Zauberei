package me.anno.generation

import me.anno.compilation.MinimalWASMCompiler
import me.anno.generation.java.JavaSourceGenerator
import me.anno.zauber.logging.LogManager
import me.anno.zauber.typeresolution.TypeResolution
import org.junit.jupiter.api.Test

/**
 * execution time: 3.6s,
 * main cost is loading Node via NVM, I think -> yesss, custom runtime is 10x faster
 * */
class WASMGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        for ((type, _) in JavaSourceGenerator.nativeJavaNumbers) {
            JavaSourceGenerator.register(
                TypeResolution.langScope, "println", listOf(type),
                "console.log(arg0.toString())" // toString() is needed for BigInt() to hide the 'n'
            )
        }
    }

    override fun generator() = MinimalWASMCompiler()

    @Test
    fun testOperationOrder() {
        testOperationOrderImpl()
    }

    @Test
    fun testMethodCall() {
        LogManager.enable("ASTSimplifier")
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
        LogManager.enable("ASTSimplifier")
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
    fun testStringOps() {
        testStringOpsImpl()
    }

    @Test
    fun testUseNativeLibrary() {
        TODO("Call into a native library")
    }

}