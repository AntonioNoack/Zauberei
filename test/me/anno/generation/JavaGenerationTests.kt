package me.anno.generation

import me.anno.compilation.MinimalJavaBuildCompiler
import me.anno.generation.java.JavaSourceGenerator
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Types
import org.junit.jupiter.api.Test

class JavaGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        for (type in listOf(
            Types.Byte, Types.Short, Types.Int, Types.Long,
            Types.Half, Types.Float, Types.Double
        )) {
            JavaSourceGenerator.register(
                TypeResolution.langScope, "println", listOf(type),
                "System.out.println(arg0);"
            )
        }
        for (type in listOf(Types.UByte, Types.UShort, Types.UInt, Types.ULong)) {
            JavaSourceGenerator.register(
                TypeResolution.langScope, "println", listOf(type),
                when (type) {
                    Types.UByte -> "System.out.println(arg0 & 0xff);"
                    Types.UShort -> "System.out.println(arg0 & 0xffff);"
                    Types.UInt -> "System.out.println(arg0 & 0xffff_ffffL);"
                    else -> "System.out.println(java.lang.Long.toUnsignedString(arg0));"
                }
            )
        }
    }

    override fun generator() = MinimalJavaBuildCompiler()

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

    // todo implement and test value classes being inlined:
    //  we explode them, and must make their body-functions static...

    // todo implement and test working with strings

}