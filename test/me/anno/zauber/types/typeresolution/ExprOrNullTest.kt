package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class ExprOrNullTest {

    @Test
    fun testExprOrNull() {
        val actual = testTypeResolution(
            """
                fun Int.plus(other: Float): Float
                
                val x: Int? = null
                val tested = x?.plus(0f)
            """.trimIndent(), true
        )
        assertEquals(unionTypes(Types.Float, NullType), actual)
    }

    @Test
    fun testExprOrOther() {
        val actual = testTypeResolution(
            """
                fun Int.plus(other: Float): Float
                
                val x: Int? = null
                val tested = x?.plus(0f) ?: 0.0
            """.trimIndent(), true
        )
        assertEquals(unionTypes(Types.Float, Types.Double), actual)
    }
}