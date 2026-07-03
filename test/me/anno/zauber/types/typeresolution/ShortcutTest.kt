package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class ShortcutTest {
    // why ever, we get a StackOverflow error for lots of these :/
    @Test
    fun testShortcutAnd() {
        val actual = testTypeResolution(
            """
            class X(val x: Int, val y: Float) {
                override fun equals(other: Any?): Boolean {
                    return other is X && other.x == x && other.y == y
                }
            }
            
            val tested = X(0,1f).equals(1)
            """.trimIndent(), reset = true
        )
        assertEquals(Types.Boolean, actual)
    }

    @Test
    fun testShortcutOr() {
        val actual = testTypeResolution(
            """
            class X(val x: Int, val y: Float) {
                override fun equals(other: Any?): Boolean {
                    return !(other !is X || other.x != x || other.y != y)
                }
            }
            
            val tested = X(0,1f).equals(1)
            """.trimIndent(), reset = true
        )
        assertEquals(Types.Boolean, actual)
    }
}