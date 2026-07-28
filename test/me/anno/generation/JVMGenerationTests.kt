package me.anno.generation

import me.anno.compilation.MinimalJVMCompiler
import me.anno.zauber.logging.LogManager
import org.junit.jupiter.api.Test

/**
 * execution time: todo get working and measure
 * */
class JVMGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        JavaGenerationTests().registerLib()
    }

    override fun generator() = MinimalJVMCompiler()

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
    fun testImplicitNumberConversion() {
        testImplicitNumberConversionImpl()
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

    // todo add .copy(name=value, name2=value2) as a special function on data classes

    // todo implement and test value classes being inlined:
    //  we explode them, and must make their body-functions static...

    // todo implement and test working with strings
    // todo test specialized classes being usable

}