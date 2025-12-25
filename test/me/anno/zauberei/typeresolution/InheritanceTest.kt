package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.StandardTypes
import me.anno.zauberei.types.Types.IntType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InheritanceTest {

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
}