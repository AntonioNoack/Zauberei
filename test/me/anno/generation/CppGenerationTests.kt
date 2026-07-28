package me.anno.generation

import me.anno.compilation.MinimalCppCompiler
import me.anno.generation.java.JavaSourceGenerator.Companion.register
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Types
import org.junit.jupiter.api.Test

/**
 * execution time: 4.1s
 * ~2s for all when preserveFolder=true, instead of 3s
 * */
class CppGenerationTests : CodeGenerationTests() {

    override fun registerLib() {
        for (type in listOf(Types.Byte, Types.Short, Types.Int)) {
            register(
                TypeResolution.langScope, "println", listOf(type),
                "#include <stdio.h>\n" +
                        "printf(\"%d\\n\",arg0);"
            )
        }
        for (type in listOf(Types.UByte, Types.UShort, Types.UInt)) {
            register(
                TypeResolution.langScope, "println", listOf(type),
                "#include <stdio.h>\n" +
                        "printf(\"%u\\n\",arg0);"
            )
        }

        register(
            TypeResolution.langScope, "println", listOf(Types.Long),
            "#include <stdio.h>\n" +
                    "printf(\"%ld\\n\",arg0);"
        )
        register(
            TypeResolution.langScope, "println", listOf(Types.ULong),
            "#include <stdio.h>\n" +
                    "printf(\"%lu\\n\",arg0);"
        )
        register(
            TypeResolution.langScope, "println", listOf(Types.String),
            "#include <stdio.h>\n" +
                    "printf(\"%s\\n\",arg0->content->content);"
        )

        for (type in listOf(Types.Half, Types.Float, Types.Double)) {
            register(
                TypeResolution.langScope, "println", listOf(type),
                "#include <stdio.h>\n" +
                        "#include <math.h>\n" +
                        "if (isinf(arg0)) printf(arg0 > 0.0 ? \"Infinity\\n\" : \"-Infinity\\n\");\n" +
                        "else if((double)(int64_t) arg0 == arg0) printf(\"%ld.0\\n\",(int64_t) arg0);\n" +
                        "else printf(\"%g\\n\",arg0);\n"
            )
        }

        register(
            TypeResolution.langScope, "flushConsole", emptyList(),
            """
                #include <stdio.h>
                printf("%.*s", zauber_ZauberKt__getObject()->printed->size, zauber_ZauberKt__getObject()->printed->buffer->content);
                zauber_ZauberKt__getObject()->printed->size = 0; // clear
            """.trimIndent()
        )

        register(
            Types.Double.clazz, "toBits", emptyList(),
            """
                return *((int64_t*) &this->content);
            """.trimIndent()
        )
    }

    override fun generator() = MinimalCppCompiler(true)

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
        // todo bug: why is it using floats for the double calculation???
        //  seemingly... maybe it just prints it with too few digits???
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

    // todo implement and test working with strings
    // todo test specialized classes being usable

}