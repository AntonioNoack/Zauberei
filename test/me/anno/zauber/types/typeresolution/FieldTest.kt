package me.anno.zauber.types.typeresolution

import me.anno.utils.MultiTest
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.zauber.types.Types
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FieldTest {

    @Test
    fun testTypeByAssignment() {
        val actual = testTypeResolution("val tested = 0")
        assertEquals(Types.Int, actual)
    }

    @Test
    fun testTypeByDeclaration() {
        val actual = testTypeResolution("val tested: Int")
        assertEquals(Types.Int, actual)
    }

    @Test
    fun testTypeByGetter() {
        val actual = testTypeResolution(
            """
                val tested
                    get() = 0
            """.trimIndent()
        )
        assertEquals(Types.Int, actual)
    }

    @Test
    fun testTypeByGetterBlock() {
        // Kotlin cannot do this one
        val actual = testTypeResolution(
            """
                val tested
                    get() {
                        return 0
                    }
            """.trimIndent()
        )
        assertEquals(Types.Int, actual)
    }

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime", "js", "java", "c++", "wasm"])
    fun testLazyWithoutDelegate(type: String) {
        // todo bug: type is not fully resolved :/
        // todo bug: at runtime, why does null get returned???
        // todo bug: compiled versions all don't work
        val code = """
                val helper = lazy { 0 }
                val tested: Int = helper.getValue()
                
                package zauber
                class Lazy<V: Any>(val getter: () -> V) {
                    var value: V? = null
                    operator fun getValue(): V {
                        if (value == null) {
                            value = getter()
                        }
                        return value!!
                    }
                }
                fun <R> lazy(getter: () -> R): Lazy {
                    return Lazy(getter)
                }
            """.trimIndent()
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(0, value.castToInt()) }
            .compile("0\n")
            .runTest(type)
    }

    @ParameterizedTest
    @ValueSource(strings = ["type", "runtime", "js", "java", "c++", "wasm"])
    fun testTypeByDelegate(type: String) {
        // todo bug: type is not fully resolved :/
        val code = """
                val tested: Int by lazy { 0 }
                
                package zauber
                class Any
                class Lazy<V: Any>(val getter: () -> V) {
                    var value: V? = null
                    operator fun getValue(): V {
                        if (value == null) {
                            value = getter()
                        }
                        return value!!
                    }
                }
                fun <R> lazy(getter: () -> R): Lazy {
                    return Lazy(getter)
                }
                fun interface Function0<R> {
                    fun call(): R
                }
                external fun println(arg0: Int)
            """.trimIndent()
        MultiTest(code)
            .type { Types.Int }
            .runtime { value -> assertEquals(0, value.castToInt()) }
            .compile("0\n")
            .runTest(type)
    }

}