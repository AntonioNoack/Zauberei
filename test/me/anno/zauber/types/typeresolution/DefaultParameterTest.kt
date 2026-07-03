package me.anno.zauber.types.typeresolution

import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import me.anno.zauber.types.Types
import org.junit.jupiter.api.Test

class DefaultParameterTest {

    @Test
    fun testDefaultParameterWithoutSelf() {
        val actual = testTypeResolution(
            """
                fun call(x: Int = 0): Float
                
                val tested = call()
            """.trimIndent()
        )
        assertEquals(Types.Float, actual)
    }

    @Test
    fun testDefaultParameterWithSelf() {
        val actual = testTypeResolution(
            """
                class X {
                    fun call(x: Int = 0): Float
                }
                
                val tested = X().call()
            """.trimIndent()
        )
        assertEquals(Types.Float, actual)
    }

    @Test
    fun testDefaultParameterWithSelfExtension() {
        val actual = testTypeResolution(
            """
                class X
                fun X.call(x: Int = 0): Float
                
                val tested = X().call()
            """.trimIndent()
        )
        assertEquals(Types.Float, actual)
    }

    @Test
    fun testDefaultConstructorParam() {
        val actual = testTypeResolution(
            """
                class X(val x: Int = 0) {
                    fun call(): Float
                }
                
                val tested = X().call()
            """.trimIndent()
        )
        assertEquals(Types.Float, actual)
    }
}