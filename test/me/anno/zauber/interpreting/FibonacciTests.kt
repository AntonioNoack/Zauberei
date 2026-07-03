package me.anno.zauber.interpreting

import me.anno.utils.MultiTest
import me.anno.utils.assertEquals
import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import me.anno.zauber.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * test some different language features to compute an actual task: Fibonacci numbers
 * 1, 1, 2, 3, 5, 8, 13, 21
 * */
class FibonacciTests {

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime", "js", "java", "c++", "wasm"])
    fun testForLoopFibonacci(type: String) {
        val code = """
        fun fib(i: Int): Int {
            var a = 1
            var b = 0
            for (k in 0 .. i) {
                val tmp = a
                a = a + b
                b = tmp
            }
            return b
        }
        val tested = fib(7)
        """.trimIndent()
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(21, value.castToInt()) }
            .compile("21\n")
            .runTest(type)
        val value = testExecute(code)
        assertEquals(21, value.castToInt())
    }

    @Test
    fun testWhileLoopFibonacci() {
        val code = """
        fun fib(i: Int): Int {
            var a = 1
            var b = 0
            var k = 0
            while (k <= i) {
                val tmp = a
                a = a + b
                b = tmp
                k++
            }
            return b
        }
        val tested = fib(7)
        """.trimIndent()
        // 1, 1, 2, 3, 5, 8, 13, 21
        val value = testExecute(code)
        assertEquals(21, value.castToInt())
    }

    @Test
    fun testRecursiveFibonacci() {
        val code = """
        fun fib(i: Int): Int {
            if (i < 2) return 1
            return fib(i-1) + fib(i-2)
        }
        val tested = fib(5)
        """.trimIndent()
        // 1, 1, 2, 3, 5, 8
        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testTailRecursiveFibonacci() {
        val code = """
        tailrec fun fib(i: Int, a: Int, b: Int): Int {
            if (i < 2) return b
            return fib(i - 1, b, a + b)
        }
        val tested = fib(5,1,1)
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testTailRecursiveDefaultParamsFibonacci() {
        val code = """
        tailrec fun fib(i: Int, a: Int = 1, b: Int = 1): Int {
            if (i < 2) return b
            return fib(i - 1, b, a + b)
        }
        val tested = fib(5)
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testWhenExpressionFibonacci() {
        val code = """
        fun fib(i: Int): Int = when (i) {
            0, 1 -> 1
            else -> fib(i - 1) + fib(i - 2)
        }
        val tested = fib(5)
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testLocalFunctionFibonacci() {
        val code = """
        fun fib(i: Int): Int {
            fun go(n: Int): Int {
                if (n < 2) return 1
                return go(n - 1) + go(n - 2)
            }
            return go(i)
        }
        val tested = fib(5)
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testMemoizedFibonacci() {

        // todo try to implement property capture on this sample...

        val code = """
        fun fib(i: Int): Int {
            val memory = IntArray(i + 1)
            fun go(n: Int): Int {
                if (n < 2) return 1
                if (memory[n] != 0) return memory[n]
                memory[n] = go(n - 1) + go(n - 2)
                return memory[n]
            }
            return go(i)
        }
        val tested = fib(5)
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }

    @Test
    fun testPropertyGetterFibonacci() {
        val code = """
        class Fib(val i: Int) {
            val value: Int
                get() {
                    if (i < 2) return 1
                    return Fib(i - 1).value + Fib(i - 2).value
                }
        }
        val tested = Fib(5).value
        """.trimIndent()

        val value = testExecute(code)
        assertEquals(8, value.castToInt())
    }


}