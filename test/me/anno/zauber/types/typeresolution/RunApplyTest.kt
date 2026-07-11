package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class RunApplyTest {
    @Test
    fun testRun() {
        val actualType = testTypeResolution(
            """
                inline fun <V, R> V.run(runnable: V.() -> R): R {
                    return runnable()
                }
                
                class Impl(val x: Int)
                
                val tested = Impl(1).run { x }
            """.trimIndent(), reset = true
        )
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testApply() {
        val actualType = testTypeResolution(
            """
                inline fun <V> V.apply(runnable: V.() -> Unit): V {
                    runnable()
                    return this
                }
                
                val tested = "Test".apply { println("Hello") }
            """.trimIndent()
        )
        assertEquals(Types.String, actualType)
    }

}