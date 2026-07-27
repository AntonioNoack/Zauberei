package me.anno.support.cpp.typeresolution

import me.anno.support.cpp.ast.rich.ArrayType
import me.anno.support.cpp.ast.rich.ArrayType.Companion.createArrayType
import me.anno.support.cpp.ast.rich.CppASTBuilder
import me.anno.support.cpp.ast.rich.CppParsingTest.Companion.testCppParsing
import me.anno.support.cpp.ast.rich.CppStandard
import me.anno.support.cpp.tokenizer.CppTokenizer
import me.anno.utils.ResolutionUtils.ctr
import me.anno.utils.ResolutionUtils.getField
import me.anno.utils.assertEquals
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.interpreting.FieldGetSetTest.Companion.assertThrowsContains
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.LambdaParameter
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.LambdaType
import org.junit.jupiter.api.Test

/**
 * Test reading some simple C++.
 * Ideally, we can run C++ libraries later on as Zauber, e.g. Bullet Physics.
 * */
class CppTypeResolutionTest {

    companion object {

        fun testCppTypeResolution(code: String): Type {
            val testScopeName = "test${ctr++}"
            val raw = """
            namespace $testScopeName {
            $code
            }
        """.trimIndent()

            val tokens = CppTokenizer(raw, "main.cpp", false).tokenize()
            println("Tokens: ${tokens.toDebugString()}")

            // todo run the preprocessor?

            CppASTBuilder(tokens, root, CppStandard.CPP11).readFileLevel()
            val testScope = root.children.first { it.name == testScopeName }
            val field = testScope[ScopeInitType.AFTER_DISCOVERY]
                .fields.first { it.name == "tested" }
            return field.valueType
                ?: error("Could not resolve type for $field")
        }

        fun Scope.resolveFieldType(name: String): Type {
            return getField(name)
                .resolveValueType(ResolutionContext.minimal)
                .resolve(null)
        }

    }

    @Test
    fun testPrimitiveFields() {
        val scope = testCppParsing(
            """
            bool a;
            char b;
            float c;
            double d;
            long e;
        """.trimIndent()
        )

        assertEquals(Types.Boolean, scope.resolveFieldType("a"))
        assertEquals(Types.Byte, scope.resolveFieldType("b"))
        assertEquals(Types.Float, scope.resolveFieldType("c"))
        assertEquals(Types.Double, scope.resolveFieldType("d"))
        assertEquals(Types.Long, scope.resolveFieldType("e"))
    }

    @Test
    fun testMultiplePointerLevels() {
        val scope = testCppParsing(
            """
            int* a;
            int** b;
            int*** c;
        """.trimIndent()
        )

        assertEquals(Types.Int.ptr(), scope.resolveFieldType("a"))
        assertEquals(Types.Int.ptr().ptr(), scope.resolveFieldType("b"))
        assertEquals(Types.Int.ptr().ptr().ptr(), scope.resolveFieldType("c"))
    }

    @Test
    fun testPointerSpacingVariants() {
        val scope = testCppParsing(
            """
            int*x;
            int *y;
            int * z;
            int* w;
        """.trimIndent()
        )

        val expected = Types.Int.ptr()

        assertEquals(expected, scope.resolveFieldType("x"))
        assertEquals(expected, scope.resolveFieldType("y"))
        assertEquals(expected, scope.resolveFieldType("z"))
        assertEquals(expected, scope.resolveFieldType("w"))
    }

    @Test
    fun testFieldWithPointerRight() {
        val actual = testCppTypeResolution("int *tested;")
        assertEquals(Types.Int.ptr(), actual)
    }

    @Test
    fun testFieldWithMixedPointerAttachments() {
        val scope = testCppParsing(
            """
            int *ptr, single;
            int* singlePtr, **ptr3;
        """.trimIndent()
        )
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("ptr"))
        assertEquals(Types.Int, scope.resolveFieldType("single"))
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("singlePtr"))
        assertEquals(Types.Int.ptr().ptr().ptr(), scope.resolveFieldType("ptr3"))
    }

    @Test
    fun testMethodWithoutParameters() {
        val scope = testCppParsing(
            """
            int answer(){
                return 42;
            }
        """.trimIndent()
        )

        val method = scope.methods.first()

        assertEquals("answer", method.name)
        assertEquals(Types.Int, method.returnType)
        assertEquals(0, method.typeParameters.size)
        assertEquals(0, method.valueParameters.size)
    }

    @Test
    fun testMethodWithMultipleParameters() {
        val scope = testCppParsing(
            """
            float mix(int a, float b, double* c){
                return b;
            }
        """.trimIndent()
        )

        val method = scope.methods.first()

        assertEquals("mix", method.name)
        assertEquals(Types.Float, method.returnType)

        assertEquals(3, method.valueParameters.size)
        assertEquals(Types.Int, method.valueParameters[0].type)
        assertEquals(Types.Float, method.valueParameters[1].type)
        assertEquals(Types.Double.ptr(), method.valueParameters[2].type)
    }

    @Test
    fun testStructFields() {
        val scope = testCppParsing(
            """
            struct Test {
                int x;
                float* y;
            };
        """.trimIndent()
        )

        val struct = scope.children.first()
        assertEquals("Test", struct.name)
        assertEquals(Types.Int, struct.resolveFieldType("x"))
        assertEquals(Types.Float.ptr(), struct.resolveFieldType("y"))
    }

    @Test
    fun testClassMethods() {
        val scope = testCppParsing(
            """
            class Math {
                int add(int a, int b){
                    return a + b;
                }
            };
        """.trimIndent()
        )

        val clazz = scope.children.first()
        assertEquals("Math", clazz.name)

        val method = clazz.methods.first()
        assertEquals("add", method.name)
        assertEquals(Types.Int, method.returnType)

        assertEquals(2, method.valueParameters.size)
        assertEquals(Types.Int, method.valueParameters[0].type)
        assertEquals(Types.Int, method.valueParameters[1].type)
    }

    @Test
    fun testNestedStruct() {
        val scope = testCppParsing(
            """
            struct Inner {
                int value;
            };

            struct Outer {
                Inner inner;
                Inner* ptr;
            };
        """.trimIndent()
        )

        val inner = scope.children.first { it.name == "Inner" }
        val outer = scope.children.first { it.name == "Outer" }

        assertEquals(inner.typeWithArgs, outer.resolveFieldType("inner"))
        assertEquals(inner.typeWithArgs.ptr(), outer.resolveFieldType("ptr"))
    }

    @Test
    fun testForwardDeclarationUsage() {
        val scope = testCppParsing(
            """
            struct Node;

            struct Node {
                Node* next;
            };
        """.trimIndent()
        )

        val node = scope.children.first { it.name == "Node" }
        assertEquals(node.typeWithArgs.ptr(), node.resolveFieldType("next"))
    }

    @Test
    fun testVoidMethod() {
        val scope = testCppParsing(
            """
            void reset(){
            }
        """.trimIndent()
        )

        val method = scope.methods.first()
        assertEquals("reset", method.name)
        assertEquals(Types.Unit, method.returnType)
    }

    @Test
    fun testVoidPointerField() {
        val scope = testCppParsing(
            """
            void* data;
        """.trimIndent()
        )

        assertEquals(Types.Unit.ptr(), scope.resolveFieldType("data"))
    }

    @Test
    fun testConstPointerVariants() {
        val scope = testCppParsing(
            """
        const int* ptrToConst;
        int* const constPtr;
        const int* const constPtrToConst;
    """.trimIndent()
        )

        // current expectation: const qualifiers are ignored by type resolution
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("ptrToConst"))
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("constPtr"))
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("constPtrToConst"))
    }

    @Test
    fun testVolatileQualifierIgnoredForNow() {
        val scope = testCppParsing(
            """
        volatile int value;
        volatile int* ptr;
    """.trimIndent()
        )

        // adjust once volatile-awareness exists
        assertEquals(Types.Int, scope.resolveFieldType("value"))
        assertEquals(Types.Int.ptr(), scope.resolveFieldType("ptr"))
    }

    @Test
    fun testConstMethodParameter() {
        val scope = testCppParsing(
            """
        int calc(const int value){
            return value;
        }
    """.trimIndent()
        )

        val method = scope.methods.first()

        assertEquals("calc", method.name)
        assertEquals(Types.Int, method.returnType)

        assertEquals(1, method.valueParameters.size)
        assertEquals(Types.Int, method.valueParameters[0].type)
    }

    @Test
    fun testConstPointerMethodParameter() {
        val scope = testCppParsing(
            """
        void process(const float* data){
        }
    """.trimIndent()
        )

        val method = scope.methods.first()
        assertEquals("process", method.name)
        assertEquals(Types.Unit, method.returnType)

        assertEquals(1, method.valueParameters.size)
        assertEquals(Types.Float.ptr(), method.valueParameters[0].type)
    }

    @Test
    fun testSimpleTypedef() {
        val scope = testCppParsing(
            """
        typedef int MyInt;
        MyInt value;
    """.trimIndent()
        )

        assertEquals(Types.Int, scope.resolveFieldType("value"))
    }

    @Test
    fun testPointerTypedef() {
        val scope = testCppParsing(
            """
        typedef int* IntPtr;
        IntPtr value;
    """.trimIndent()
        )

        assertEquals(Types.Int.ptr(), scope.resolveFieldType("value"))
    }

    @Test
    fun testStructTypedef() {
        val scope = testCppParsing(
            """
        typedef struct {
            int x;
        } MyStruct;

        MyStruct value;
    """.trimIndent()
        )

        val expectedScope = scope.children.first { it.name == "MyStruct" }
        assertEquals(listOf("x"), expectedScope.fields.map { it.name })
        assertEquals(expectedScope.typeWithArgs, scope.resolveFieldType("value"))
    }

    @Test
    fun testTypedefPointerLevels() {
        val scope = testCppParsing(
            """
        typedef int Base;
        typedef Base* BasePtr;
        typedef BasePtr* BasePtrPtr;

        BasePtrPtr value;
    """.trimIndent()
        )

        assertEquals(Types.Int.ptr().ptr(), scope.resolveFieldType("value"))
    }

    @Test
    fun testFunctionPointerField() {
        val scope = testCppParsing(
            """
        int (*callback)(float value);
    """.trimIndent()
        )

        val type = scope.resolveFieldType("callback")

        // replace with stronger assertions once function-pointer types exist
        val params = listOf(LambdaParameter("value", Types.Float, -1))
        assertEquals(LambdaType(null, params, Types.Int), type)
    }

    @Test
    fun testFunctionPointerField2() {
        val scope = testCppParsing(
            """
        int* (*callback)(float value);
    """.trimIndent()
        )

        val type = scope.resolveFieldType("callback")

        // replace with stronger assertions once function-pointer types exist
        val params = listOf(LambdaParameter("value", Types.Float, -1))
        assertEquals(LambdaType(null, params, Types.Int.ptr()), type)
    }

    @Test
    fun testFunctionPointerParameter() {
        val scope = testCppParsing(
            """
        void install(int (*callback)(int)){
        }
    """.trimIndent()
        )

        val method = scope.methods.first()

        assertEquals("install", method.name)
        assertEquals(Types.Unit, method.returnType)

        assertEquals(1, method.valueParameters.size)
        val params = listOf(LambdaParameter("__0", Types.Int, -1))
        assertEquals(LambdaType(null, params, Types.Int), method.valueParameters[0].type)
    }

    @Test
    fun testFunctionPointerTypedef() {
        val scope = testCppParsing(
            """
        typedef int (*BinaryOp)(int a, int b);

        BinaryOp op;
    """.trimIndent()
        )

        val type = scope.resolveFieldType("op")
        val params = listOf(
            LambdaParameter("a", Types.Int, -1),
            LambdaParameter("b", Types.Int, -1)
        )
        assertEquals(LambdaType(null, params, Types.Int), type)
    }

    @Test
    fun testMethodReturningFunctionPointer() {
        val scope = testCppParsing(
            """
        int (*factory(int x))(float){
            return nullptr;
        }
    """.trimIndent()
        )

        val method = scope.methods.first()

        assertEquals("factory", method.name)

        // replace once dedicated function-pointer return types exist
        assertEquals("int(*)(float)", method.returnType.toString())
    }

    @Test
    fun testArray() {
        val scope = testCppParsing(
            """
        int a[5];
        int b[3][2];
    """.trimIndent()
        )

        val expectedA = ArrayType(Types.Int, listOf(5))
        assertEquals(expectedA, scope.resolveFieldType("a"))

        val expectedB = ArrayType(Types.Int, listOf(3, 2))
        assertEquals(expectedB, scope.resolveFieldType("b"))
    }

    @Test
    fun testNegativeArraySize() {
        assertThrowsContains<IllegalStateException>(listOf("Invalid size expression")) {
            testCppParsing("int a[-1];")
        }
    }

    @Test
    fun testArrayOfFunctionPointers() {
        val scope = testCppParsing("void (*handlers[4])(int);")

        val type = scope.resolveFieldType("handlers")
        val lambdaType = LambdaType(
            null, listOf(
                LambdaParameter("__0", Types.Int, -1),
            ), Types.Unit
        )
        assertEquals(createArrayType(lambdaType, NumberExpression("4", scope, -1)), type)
    }

}