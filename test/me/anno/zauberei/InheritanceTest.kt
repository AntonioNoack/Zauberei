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
import me.anno.zauberei.types.impl.AndType.Companion.andTypes
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes
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

        fun isSubTypeOf(expected: Type, actual: Type): Boolean {
            return isSubTypeOf(
                expected, actual, emptyList(),
                emptyList(), InsertMode.READ_ONLY
            )
        }

        fun unionTypes(typeA: Type, typeB: Type, typeC: Type): Type {
            return unionTypes(typeA, unionTypes(typeB, typeC))
        }

        fun andTypes(typeA: Type, typeB: Type, typeC: Type): Type {
            return andTypes(typeA, andTypes(typeB, typeC))
        }
    }

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
            "V", NullableAnyType, null, scope, -1
        )
        scope.typeParameters = listOf(anyOrNullParameter)

        // parameter is available, writable -> true
        assertTrue(
            isSubTypeOf(
                map(anyOrNullGeneric), map(classA),
                listOf(anyOrNullParameter), getClearedList(), InsertMode.STRONG
            )
        )
        assertEquals(classA, list[0])

        // using the same list, a B is fine, too
        assertTrue(
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
            "F", FloatType, null, scope, -1
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

    @Test
    fun testUnionTypes() {
        val scope = """
    class A
    class B
    class C
""".testInheritance()
        val classA = scope["A"]
        val classB = scope["B"]
        val classC = scope["C"]

        assertTrue(isSubTypeOf(classA, classA))
        assertFalse(isSubTypeOf(classB, classA))
        assertFalse(isSubTypeOf(classC, classA))

        assertTrue(isSubTypeOf(unionTypes(classA, classB), classA))
        assertTrue(isSubTypeOf(unionTypes(classA, classB), classB))
        assertFalse(isSubTypeOf(unionTypes(classA, classB), classC))

        assertFalse(isSubTypeOf(classA, unionTypes(classA, classB)))
        assertFalse(isSubTypeOf(classB, unionTypes(classA, classB)))

        assertTrue(isSubTypeOf(unionTypes(classA, classB), unionTypes(classA, classB)))
        assertTrue(isSubTypeOf(unionTypes(classA, classB, classC), unionTypes(classA, classB)))
    }

    @Test
    fun testAndTypes() {
        val scope = """
    class A
    class B
    class C
""".testInheritance()
        val classA = scope["A"]
        val classB = scope["B"]
        val classC = scope["C"]

        assertTrue(isSubTypeOf(classA, classA))
        assertFalse(isSubTypeOf(classB, classA))
        assertFalse(isSubTypeOf(classC, classA))

        assertTrue(isSubTypeOf(classA, andTypes(classA, classB)))
        assertTrue(isSubTypeOf(classB, andTypes(classA, classB)))
        assertFalse(isSubTypeOf(classC, andTypes(classA, classB)))

        assertFalse(isSubTypeOf(andTypes(classA, classB), classA))
        assertFalse(isSubTypeOf(andTypes(classA, classB), classB))

        assertTrue(isSubTypeOf(andTypes(classA, classB), andTypes(classA, classB)))
        assertTrue(isSubTypeOf(andTypes(classA, classB), andTypes(classA, classB, classC)))
    }

    @Test
    fun testNotTypes() {
        val scope = "class A".testInheritance()
        val classA = scope["A"]

        // todo test nots with unions(?)

        assertTrue(isSubTypeOf(classA, classA))
        assertFalse(isSubTypeOf(classA.not(), classA))
        assertFalse(isSubTypeOf(classA, classA.not()))
        assertTrue(isSubTypeOf(classA.not(), classA.not()))
    }

}