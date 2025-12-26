package me.anno.zauberei

import me.anno.zauberei.typeresolution.TypeResolutionTest
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.StringType
import me.anno.zauberei.types.impl.AndType.Companion.andTypes
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeParserTest {

    companion object {
        fun String.parseType(): Type {
            return TypeResolutionTest.testTypeResolution(
                """
                val tested: $this
            """.trimIndent()
            )
        }
    }

    @Test
    fun testSimpleType() {
        assertEquals(FloatType, "Float".parseType())
        assertEquals(StringType, "String".parseType())
    }

    @Test
    fun testNullableType() {
        assertEquals(unionTypes(StringType, NullType), "String?".parseType())
        assertEquals(unionTypes(FloatType, NullType), "Float?".parseType())
    }

    @Test
    fun testOnlyNullType() {
        assertEquals(NullType, "Nothing?".parseType())
    }

    @Test
    fun testUnionType() {
        assertEquals(unionTypes(FloatType, StringType), "Float|String".parseType())
    }

    @Test
    fun testAndType() {
        assertEquals(andTypes(FloatType, StringType), "Float&String".parseType())
    }

    @Test
    fun testNotType() {
        assertEquals(FloatType.not(), "!Float".parseType())
    }
}