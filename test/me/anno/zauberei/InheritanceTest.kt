package me.anno.zauberei

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.InsertMode
import me.anno.zauberei.typeresolution.TypeResolutionTest.Companion.ctr
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.NullType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InheritanceTest {

    companion object {
        fun String.testInheritance(): Scope {
            val testScopeName = "test${ctr++}"
            val tokens = Tokenizer(
                """
            package $testScopeName
            
            $this
        """.trimIndent(), "?"
            ).tokenize()
            ASTBuilder(tokens, root).readFileLevel()
            return root.children.first { it.name == testScopeName }
        }

        operator fun Scope.get(name: String): ClassType {
            return children.first { it.name == name }.typeWithoutArgs
        }

        fun isSubTypeOf(type1: Type, type2: Type): Boolean {
            return isSubTypeOf(
                type1, type2, emptyList(),
                emptyList(), InsertMode.READ_ONLY
            )
        }
    }

    // todo add sub-type tests with generics, union types, etc...

    @Test
    fun testIdentity() {
        assertTrue(isSubTypeOf(NullType, NullType))
        assertTrue(isSubTypeOf(AnyType, AnyType))
        assertTrue(isSubTypeOf(IntType, IntType))
    }

    @Test
    fun testSuperClass() {
        val scope = """
    open class A
    class B : A()
""".testInheritance()
        assertTrue(isSubTypeOf(scope["A"], scope["B"]))
        assertFalse(isSubTypeOf(scope["B"], scope["A"]))
    }

    @Test
    fun testSuperClassX2() {
        val scope = """
    open class A
    open class B : A()
    class C: B()
""".testInheritance()
        assertTrue(isSubTypeOf(scope["A"], scope["B"]))
        assertTrue(isSubTypeOf(scope["A"], scope["C"]))
        assertTrue(isSubTypeOf(scope["B"], scope["C"]))
        assertFalse(isSubTypeOf(scope["B"], scope["A"]))
        assertFalse(isSubTypeOf(scope["C"], scope["A"]))
        assertFalse(isSubTypeOf(scope["C"], scope["B"]))
    }

    @Test
    fun testInterface() {
        val scope = """
    interface A
    class B : A
""".testInheritance()
        assertTrue(isSubTypeOf(scope["A"], scope["B"]))
        assertFalse(isSubTypeOf(scope["B"], scope["A"]))
    }

    @Test
    fun testInterfaceX2() {
        val scope = """
    interface A
    interface B : A
    class C : B
""".testInheritance()
        assertTrue(isSubTypeOf(scope["A"], scope["B"]))
        assertTrue(isSubTypeOf(scope["B"], scope["C"]))
        assertTrue(isSubTypeOf(scope["A"], scope["C"]))
        assertFalse(isSubTypeOf(scope["B"], scope["A"]))
        assertFalse(isSubTypeOf(scope["C"], scope["A"]))
        assertFalse(isSubTypeOf(scope["C"], scope["B"]))
    }

}