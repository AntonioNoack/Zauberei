package me.anno.zauberei

import me.anno.zauberei.TypeResolutionTest.Companion.testTypeResolution
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PackageScopeTypeResolutionTest {
    @Test
    fun testPackageScopeField() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                val value = 0
                val tested = value
            """.trimIndent()
            )
        )
    }

    @Test
    fun testPackageScopeMethod() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                fun method() = 0
                val tested = method()
            """.trimIndent()
            )
        )
    }

}