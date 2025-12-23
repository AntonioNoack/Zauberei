package me.anno.zauberei

import me.anno.zauberei.TypeResolutionTest.Companion.testTypeResolution
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionTypeResolutionTest {

    // todo also check methods and fields without underlying any obvious class (class is package)
    // todo also check accessing class methods and fields from an extension scope

    // todo also check under-defined extension fields/methods, so those, that need ResolutionContext.targetType

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
    fun testExtensionFieldOnInterfaces() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                interface Func
                class Impl(): Func
                val Func.value get() = 0
                
                val tested = Impl().value
            """.trimIndent()
            )
        )
    }

    @Test
    fun testUnderdefinedExtensionMethodsByMethod() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl()
                fun <V> Impl.get(): List<V>
                
                fun sum(values: List<Int>): Int
                
                val tested = sum(Impl().get())
            """.trimIndent()
            )
        )
    }

    @Test
    fun testUnderdefinedExtensionFieldsByField() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl()
                val <V> Impl.value: List<V>
                
                fun sum(values: List<Int>): Int
                
                val tested = sum(Impl().value)
            """.trimIndent()
            )
        )
    }

    @Test
    fun testUnderdefinedExtensionMethodsByClass() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl<V>()
                fun Impl.get(): List<V> = emptyList()
                
                fun <V> emptyList(): List<V>
                fun sum(values: List<Int>): Int
                
                val tested = sum(Impl().get())
            """.trimIndent()
            )
        )
    }

    @Test
    fun testUnderdefinedExtensionFieldsByClass() {
        assertEquals(
            IntType,
            testTypeResolution(
                """
                class Impl<V>()
                val Impl.value: List<V>
                    get() = emptyList()
                
                fun <V> emptyList(): List<V>
                fun sum(values: List<Int>): Int
                
                val tested = sum(Impl().value)
            """.trimIndent()
            )
        )
    }

}