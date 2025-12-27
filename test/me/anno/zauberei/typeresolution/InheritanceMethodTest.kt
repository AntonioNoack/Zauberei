package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.StandardTypes
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InheritanceMethodTest {

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
                    fun call(): Int
                }
                
                val tested = A().call()
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
                    fun call(): Int
                }
                
                val tested = A<Int>().call()
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
                    fun call(): Int
                }
                class B: A()
                
                val tested = B().call()
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
                    fun call(): Int
                }
                class B<X>: A<X>()
                
                val tested = B<Float>().call()
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
                    fun call(): Int
                }
                open class B: A()
                class C: B()
                
                val tested = C().call()
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
                    fun call(): Int
                }
                open class B<J>: A<J>()
                class C<K>: B<K>()
                
                val tested = C<Float>().call()
            """.trimIndent()
            )
        )
    }
}