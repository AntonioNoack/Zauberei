package me.anno.generation

import me.anno.compilation.GLSLTarget
import me.anno.compilation.MinimalGLSLCompiler
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

/**
 * todo we must implement lots of things on our own...
 * todo we must bind native functions like sin, cos etc in all targets
 * */
class GLSLGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        // todo stdlib is different from C++...
        CppGenerationTests().registerLib()
    }

    override fun generator() = MinimalGLSLCompiler(GLSLTarget.COMPUTE_SHADER)

    @Test
    fun testOperationOrder() {
        testOperationOrderImpl()
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
    fun testStringOps() {
        testStringOpsImpl()
    }

    @Test
    fun testUseNativeLibrary() {
        val code = """
            
            @CInclude("<ctype.h>")
            external fun isdigit(c: Char): Int
            
            fun main() {
                println(if(isdigit('7')) 2 else 1)
            }
            
            package zauber
            annotation class CInclude(val source: String)
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("2\n", printed)
    }
}