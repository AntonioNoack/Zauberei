package me.anno.zauber.types.typeresolution

import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.ResolutionUtils.testTypeResolutionGetField
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class TypeResolutionTest {

    private fun expectBool(type: Type) {
        assertEquals(Types.Boolean, type)
    }

    private fun expectType(type: Type, expected: Type) {
        assertEquals(expected, type)
    }

    @Test
    fun testConstants() {
        expectBool(testTypeResolution("val tested = true"))
        expectBool(testTypeResolution("val tested = false"))
        expectType(testTypeResolution("val tested = null"), NullType)
        expectType(testTypeResolution("val tested = 0"), Types.Int)
        expectType(testTypeResolution("val tested = 0L"), Types.Long)
        expectType(testTypeResolution("val tested = 0f"), Types.Float)
        expectType(testTypeResolution("val tested = 0.0f"), Types.Float)
        expectType(testTypeResolution("val tested = 0d"), Types.Double)
        expectType(testTypeResolution("val tested = 0.0"), Types.Double)
        expectType(testTypeResolution("val tested = 1e3"), Types.Double)
        expectType(testTypeResolution("val tested = ' '"), Types.Char)
        expectType(testTypeResolution("val tested = \"Test 123\""), Types.String)
    }

    @Test
    fun testNullableTypes() {
        val actual = testTypeResolution("val tested: Boolean?")
        assertEquals(UnionType(listOf(Types.Boolean, NullType)), actual)
    }

    @Test
    fun testConstructorWithParameter() {
        val actual = testTypeResolution(
            """
            val tested = IntArray(5)
        """.trimIndent(), true
        )
        assertEquals(Types.Array.withTypeParameter(Types.Int), actual)
    }

    @Test
    fun testGetOperator() {
        val type = testTypeResolution(
            """
            class Node(val value: Int)
            val x: Node
            val tested = x.value
        """.trimIndent()
        )
        assertEquals(Types.Int, type)
    }

    @Test
    fun testIfNullOperator() {
        val type =
            testTypeResolution(
                """
            val x: Int?
            val tested = x ?: 0f
        """.trimIndent()
            )
        assertEquals(unionTypes(Types.Int, Types.Float), type)
    }

    @Test
    fun testNullableGetOperator() {
        val type =
            testTypeResolution(
                """
            class Node(val parent: Node?, val value: Int)
            val x: Node?
            val tested = x?.parent?.value
        """.trimIndent()
            )
        assertEquals(unionTypes(Types.Int, NullType), type)
    }

    @Test
    fun testCompareOperators() {
        expectBool(testTypeResolution("val tested = 0 < 1"))
        expectBool(testTypeResolution("val tested = 0 <= 1"))
        expectBool(testTypeResolution("val tested = 0 > 1"))
        expectBool(testTypeResolution("val tested = 0 >= 1"))
        expectBool(testTypeResolution("val tested = 0 == 1"))
        expectBool(testTypeResolution("val tested = 0 != 1"))
        expectBool(testTypeResolution("val tested = 0 === 1"))
        expectBool(testTypeResolution("val tested = 0 !== 1"))
    }

    @Test
    fun testCompareOperatorsMixed() {
        expectBool(testTypeResolution("val tested = 0f < 1"))
        expectBool(testTypeResolution("val tested = 0f <= 1"))
        expectBool(testTypeResolution("val tested = 0f > 1"))
        expectBool(testTypeResolution("val tested = 0f >= 1"))
        expectBool(testTypeResolution("val tested = 0f == 1"))
        expectBool(testTypeResolution("val tested = 0f != 1"))
        expectBool(testTypeResolution("val tested = 0f === 1"))
        expectBool(testTypeResolution("val tested = 0f !== 1"))
    }

    @Test
    fun testValueType() {
        val field = testTypeResolutionGetField("value val tested = \"\"", true)
        check(field.flags.hasFlag(Flags.VALUE))
    }
}