package me.anno.zauber.interpreting

import me.anno.utils.StringStyles
import me.anno.utils.assertEquals
import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import me.anno.zauber.logging.LogManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class FieldGetSetTest {

    companion object {

        fun assertContains(content: String, full: String) {
            assert(content in full) { "Expected '$content' in '$full'" }
        }

        inline fun <reified V : Throwable> assertThrowsMessage(validateMessage: (String) -> Unit, run: () -> Unit) {
            try {
                run()
            } catch (e: Throwable) {
                check(e is V) { "Incorrect exception type was thrown: $e" }
                validateMessage(StringStyles.removeStyles(e.message ?: ""))
                return
            }
            fail { "Expected an exception to be thrown" }
        }

        inline fun <reified V : Throwable> assertThrowsContains(listOf: List<String>, run: () -> Unit) {
            try {
                run()
            } catch (e: Throwable) {
                check(e is V) { "Incorrect exception type was thrown: $e" }
                val message = StringStyles.removeStyles(e.message ?: "")
                for (part in listOf) {
                    if (!message.contains(part, ignoreCase = true)) throw e
                }
                return
            }
            fail { "Expected an exception to be thrown" }
        }

        inline fun <reified V : Throwable> assertThrowsContains(part: String, run: () -> Unit) {
            assertThrowsContains<V>(listOf(part), run)
        }
    }

    @Test
    fun testGetSetClassField() {
        val code = """
            class Vector(var x: Int, var y: Int)
            fun calculate(v: Vector): Int {
                v.x = 1
                return v.x + v.y
            }
            val tested = calculate(Vector(3,4))
        """.trimIndent()
        val value = testExecute(code)
        assertEquals(5, value.castToInt())
    }

    @Test
    fun testGetSetClassFieldMultiply() {
        val code = """
            class Vector(var x: Int, var y: Int)
            fun calculate(v: Vector): Int {
                v.x *= 2
                return v.x + v.y
            }
            val tested = calculate(Vector(3,4))
        """.trimIndent()
        val value = testExecute(code)
        assertEquals(10, value.castToInt())
    }

    @Test
    fun testTrySetImmutableField() {
        assertThrowsMessage<IllegalStateException>({ message ->
            assertContains("Expected ", message)
            assertContains(".x to be mutable", message)
        }) {
            val code = """
            class Vector(val x: Int, val y: Int)
            fun calculate(v: Vector): Int {
                v.x = 1
                return v.x + v.y
            }
            val tested = calculate(Vector(3,4))
        """.trimIndent()
            testExecute(code)
        }
    }

    @Test
    fun testGetterAndSetterWithDelegate() {
        // todo why does it crash?
        val actual = testExecute(
            """
            var tmp: Int by LazyInit { 3 }
            val tested: Int
                get() {
                    val v = tmp
                    tmp += 5
                    return v + tmp
                }
            
            package zauber
            class LazyInit<V>(val initialValue: () -> V) {
                
                var hasValue = false
                lateinit var value: V
                
                external operator fun getValue(): V {
                    if (!hasValue) {
                        value = initialValue()
                        hasValue = true
                    }
                    return value
                }
                external operator fun setValue(value: V) {
                    this.value = value
                }
            }
            """.trimIndent()
        )
        assertEquals(8, actual.castToInt())
    }
}