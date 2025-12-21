package me.anno.zauberei

import me.anno.zauberei.Compile.root
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.impl.UnionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeEqualsTest {
    @Test
    fun testClassType() {
        fun gen() = ClassType(root, null)
        assertEquals(gen(), gen())
    }

    @Test
    fun testUnionType() {
        fun gen() = UnionType(listOf(BooleanType, NullType))
        assertEquals(gen(), gen())
    }
}