package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionTest {

    // todo also check accessing class methods and fields from an extension scope
    // todo also check shadowing over outer and class scope... which one does Kotlin choose?

    @Test
    fun testExtensionMethods() {
        assertEquals(
            IntType,
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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
            TypeResolutionTest.Companion.testTypeResolution(
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