package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.StringType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClassCastTest {
    @Test
    fun testTypeIsCastInBranch() {
        assertEquals(
            unionTypes(FloatType, StringType),
            TypeResolutionTest.testTypeResolution(
                """
                fun Int.plus(other: Float): Float
                
                val x: Int? = null
                val tested = if(x == null) "Test" else x+1f
            """.trimIndent()
            )
        )
    }

    @Test
    fun testTypeIsCastAfterReturningBranch() {
        // todo can we somehow test this?? we need to resolve the x+1f inside the getter...
        assertEquals(
            unionTypes(FloatType, NullType),
            TypeResolutionTest.testTypeResolution(
                """
                fun Int.plus(other: Float): Float
                
                val x: Int? = null
                val tested: Float? get() {
                    if(x == null) return null
                    return x+1f
                }
            """.trimIndent()
            )
        )
    }
}