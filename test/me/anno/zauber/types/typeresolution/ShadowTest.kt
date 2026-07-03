package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class ShadowTest {
    @Test
    fun testShadowedFields() {
        val actualType = testTypeResolution(
            """
                fun main(x: Int): Float {
                    val x = x+1
                    return x+1f
                }
                
                operator fun Int.plus(other: Float): Float
                operator fun Int.plus(other: Int): Int
                
                val tested = main(0)
            """.trimIndent(), true
        )
        assertEquals(Types.Float, actualType)
    }
}