package me.anno.zauberei

import me.anno.zauberei.TypeResolutionTest.Companion.testTypeResolution
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionTypeResolutionTest {

    // todo also check methods and fields without underlying any obvious class (class is package)

    @Test
    fun testExtensionMethods() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl()
                fun Impl.get() = 0
                
                val tested = Impl().get()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testExtensionMethodsOnSuperClass() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Super()
                class Impl(): Super()
                fun Super.get() = 0
                
                val tested = Impl().get()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testExtensionMethodsOnInterfaces() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl(): Func
                interface Func
                fun Func.get() = 0
                
                val tested = Impl().get()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testExtensionFields() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl()
                val Impl.value get() = 0
                
                val tested = Impl().value
            """.trimIndent()
            )
        )
    }

    @Test
    fun testExtensionFieldsOnSuperClass() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Super()
                class Impl(): Super()
                val Super.value get() = 0
                
                val tested = Impl().value
            """.trimIndent()
            )
        )
    }

    @Test
    fun testExtensionFunctionsOnInterfaces() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl(): Func
                interface Func
                val Func.value get() = 0
                
                val tested = Impl().value
            """.trimIndent()
            )
        )
    }
}