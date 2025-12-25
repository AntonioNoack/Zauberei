package me.anno.zauberei

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.typeresolution.FillInParameterList
import me.anno.zauberei.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauberei.typeresolution.InsertMode
import me.anno.zauberei.typeresolution.TypeResolutionTest.Companion.ctr
import me.anno.zauberei.typeresolution.TypeResolutionTest.Companion.defineArrayListConstructors
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.StandardTypes.standardClasses
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.NullableAnyType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType
import org.junit.jupiter.api.Assertions.*
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

    @Test
    fun testTypeParameters() {
        defineArrayListConstructors()
        val listType = standardClasses["ArrayList"]!!
        assertEquals(1, listType.typeParameters.size)

        val scope = """
    open class A
    class B : A()
""".testInheritance()
        val listOfA = ClassType(listType, listOf(scope["A"]))
        val listOfB = ClassType(listType, listOf(scope["B"]))
        assertTrue(isSubTypeOf(listOfA, listOfA))
        assertTrue(isSubTypeOf(listOfB, listOfB))
        assertTrue(isSubTypeOf(listOfA, listOfB))
        assertFalse(isSubTypeOf(listOfB, listOfA))
    }

    private val list = FillInParameterList(1)
    fun getClearedList(): FillInParameterList {
        list.clear()
        return list
    }

    fun testInferred(map: (Type) -> Type) {
        val scope = "open class A; class B: A()".testInheritance()
        val classA = scope["A"]
        val classB = scope["B"]

        // first check without bounds
        val anyOrNullGeneric = GenericType(scope, "V")
        val anyOrNullParameter = Parameter(
            false, true, false,
            "V", NullableAnyType, null, -1
        )
        scope.typeParameters = listOf(anyOrNullParameter)

        // no type params to be defined -> cannot check -> false
        assertFalse(isSubTypeOf(anyOrNullGeneric, classA))

        // parameter is available, but readonly -> cannot check -> false
        assertFalse(
            isSubTypeOf(
                map(anyOrNullGeneric), map(classA),
                listOf(anyOrNullParameter), getClearedList(), InsertMode.READ_ONLY
            )
        )
        assertNull(list[0])

        // parameter is available, writable -> true
        assertTrue(
            isSubTypeOf(
                map(anyOrNullGeneric), map(classA),
                listOf(anyOrNullParameter), getClearedList(), InsertMode.STRONG
            )
        )
        assertEquals(classA, list[0])

        // using the same list, a B is fine, too
        assertFalse(
            isSubTypeOf(
                map(anyOrNullGeneric), map(classB),
                listOf(anyOrNullParameter), list, InsertMode.READ_ONLY
            )
        )
        assertEquals(classA, list[0])

        // using the same list, a weak Int is not fine
        assertFalse(
            isSubTypeOf(
                map(anyOrNullGeneric), map(IntType),
                listOf(anyOrNullParameter), list, InsertMode.WEAK
            )
        )
        assertEquals(classA, list[0])

        // but a strong Int is fine
        assertTrue(
            isSubTypeOf(
                map(anyOrNullGeneric), map(IntType),
                listOf(anyOrNullParameter), list, InsertMode.STRONG
            )
        )
        assertEquals(UnionType(listOf(classA, IntType)), list[0])

        // now check generics with bounds
        val floatGeneric = GenericType(scope, "F")
        val floatParameter = Parameter(
            false, true, false,
            "F", FloatType, null, -1
        )
        scope.typeParameters = listOf(floatParameter)

        // even the strong mode must respect type bounds
        assertFalse(
            isSubTypeOf(
                map(floatGeneric), map(IntType),
                listOf(floatParameter), getClearedList(), InsertMode.STRONG
            )
        )
        assertNull(list[0])

        // inserting Floats is ofc fine, both strong and weak
        assertTrue(
            isSubTypeOf(
                map(floatGeneric), map(FloatType),
                listOf(floatParameter), getClearedList(), InsertMode.WEAK
            )
        )
        assertTrue(
            isSubTypeOf(
                map(floatGeneric), map(FloatType),
                listOf(floatParameter), getClearedList(), InsertMode.STRONG
            )
        )
        // but still not when insertMode = read only
        assertFalse(
            isSubTypeOf(
                map(floatGeneric), map(FloatType),
                listOf(floatParameter), getClearedList(), InsertMode.READ_ONLY
            )
        )

    }

    @Test
    fun testInferredAsPrimaryType() {
        testInferred { it }
    }

    @Test
    fun testInferredAsParameterType() {
        defineArrayListConstructors()
        val listType = standardClasses["ArrayList"]!!
        testInferred { ClassType(listType, listOf(it)) }
    }

}