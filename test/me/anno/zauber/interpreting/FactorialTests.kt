package me.anno.zauber.interpreting

import me.anno.utils.MultiTest
import me.anno.zauber.types.Types
import me.anno.utils.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FactorialTests {

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime", "js"/*, "java", "c++", "wasm"*/])
    fun testFactorialAsRecursiveFunction(type: String) {
        // todo for this to work, we must implement SimpleCompare in all targets
        val code = """
            fun fac(i: Int): Int {
                if (i <= 1) return 1
                return i * fac(i-1)
            }
            val tested = fac(5)
        """.trimIndent()
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(5 * 4 * 3 * 2, value.castToInt()) }
            .compile("120\n")
            .runTest(type)
    }

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime", "js"/*, "java", "c++", "wasm"*/])
    fun testFactorialAsWhileLoop(type: String) {
        val code = """
            fun fac(i: Int): Int {
                var f = 1
                var i = i
                while (i > 1) {
                    f *= i
                    i--
                }
                return f
            }
            val tested = fac(10)
        """.trimIndent()
        val value1 = 10 * 9 * 8 * 7 * 6 * 5 * 4 * 3 * 2
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(value1, value.castToInt()) }
            .compile("$value1\n")
            .runTest(type)
    }

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime"/*, "js", "java", "c++", "wasm"*/])
    fun testFactorialAsForLoop(type: String) {
        val code = """
            fun fac(n: Int): Int {
                var product = 1
                for (k in 2 .. n) {
                    product *= k
                }
                return product
            }
            val tested = fac(10)
        """.trimIndent()

        val value1 = 10 * 9 * 8 * 7 * 6 * 5 * 4 * 3 * 2
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(value1, value.castToInt()) }
            .compile("$value1\n")
            .runTest(type)
    }

}