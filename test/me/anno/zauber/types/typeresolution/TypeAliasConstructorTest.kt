package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class TypeAliasConstructorTest {

    @Test
    fun testSimpleTypeAlias() {
        val actualType = testTypeResolution(
            """
            class A
            typealias B = A
            val tested = B()
        """.trimIndent()
        )
        check(actualType is ClassType && actualType.clazz.name == "A") {
            "Expected $actualType to be A"
        }
    }

    @Test
    fun testSimpleTypeAliasRev() {
        val actualType = testTypeResolution(
            """
            typealias B = A
            class A
            val tested = B()
        """.trimIndent()
        )
        check(actualType is ClassType && actualType.clazz.name == "A") {
            "Expected $actualType to be A"
        }
    }

    @Test
    fun testTypeRecursive() {
        val actualType = testTypeResolution(
            """
            typealias D = C
            typealias C = B
            typealias B = A
            class A
            val tested = D()
        """.trimIndent()
        )
        check(actualType is ClassType && actualType.clazz.name == "A") {
            "Expected $actualType to be A"
        }
    }

    @Test
    fun testTypeAliasInsideGetter() {
        // (Kotlin doesn't even allow this)
        val actualType = testTypeResolution(
            """
            val tested get() {
                typealias Int32 = Int
                return arrayOf<Int32>()
            }
        """.trimIndent(), reset = true
        )
        assertEquals(Types.Array.withTypeParameter(Types.Int), actualType)
    }
}