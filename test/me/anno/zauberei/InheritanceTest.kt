package me.anno.zauberei

import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.InsertMode
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.impl.NullType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InheritanceTest {

    // todo test all isSubTypeOf-scenarios

    @Test
    fun testIdentity() {
        assertTrue(
            isSubTypeOf(
                NullType, NullType,
                emptyList(), emptyList(), InsertMode.READ_ONLY
            )
        )
        assertTrue(
            isSubTypeOf(
                AnyType, AnyType,
                emptyList(), emptyList(), InsertMode.READ_ONLY
            )
        )
        assertTrue(
            isSubTypeOf(
                IntType, IntType,
                emptyList(), emptyList(), InsertMode.READ_ONLY
            )
        )
    }

    // todo implement these...

    @Test
    fun testSuperClass() {
"""
    open class A
    class B : A()
""".trimIndent()
    }

    @Test
    fun testSuperClassX2() {
        """
    open class A
    open class B : A()
    class C: B()
""".trimIndent()
    }

    @Test
    fun testInterface() {
        """
    interface A
    class B : A
""".trimIndent()
    }

    @Test
    fun testInterfaceX2() {
        """
    interface A
    interface B : A
    class C : B
""".trimIndent()
    }

}