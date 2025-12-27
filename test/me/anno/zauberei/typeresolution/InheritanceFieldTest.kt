package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.StandardTypes
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InheritanceFieldTest {

    @BeforeEach
    fun init() {
        // ensure std types are loaded for testing
        StandardTypes.standardClasses
        check(IntType.classHasNoTypeParams())
    }

    @Test
    fun testDirectCall() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                class A {
                    val size: Int
                }
                
                val tested = A().size
            """.trimIndent()
            )
        )
    }

    @Test
    fun testDirectCallWithGenerics() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                class A<V> {
                    val size: Int
                }
                
                val tested = A<Int>().size
            """.trimIndent()
            )
        )
    }

    @Test
    fun testSuperCallX1() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                open class A {
                    val size: Int
                }
                class B: A()
                
                val tested = B().size
            """.trimIndent()
            )
        )
    }

    @Test
    fun testSuperCallX1WithGenerics() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                open class A<V> {
                    val size: Int
                }
                class B<X>: A<X>()
                
                val tested = B<Float>().size
            """.trimIndent()
            )
        )
    }

    @Test
    fun testSuperCallX2() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                open class A {
                    val size: Int
                }
                open class B: A()
                class C: B()
                
                val tested = C().size
            """.trimIndent()
            )
        )
    }

    @Test
    fun testSuperCallX2WithGenerics() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                open class A<I> {
                    val size: Int
                }
                open class B<J>: A<J>()
                class C<K>: B<K>()
                
                val tested = C<Float>().size
            """.trimIndent()
            )
        )
    }
}