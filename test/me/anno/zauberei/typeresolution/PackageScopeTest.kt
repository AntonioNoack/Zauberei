package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PackageScopeTest {
    @Test
    fun testPackageScopeField() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
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
            TypeResolutionTest.testTypeResolution(
                """
                fun method() = 0
                val tested = method()
            """.trimIndent()
            )
        )
    }

}