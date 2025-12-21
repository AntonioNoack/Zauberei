package me.anno.zauberei

import me.anno.zauberei.Compile.root
import me.anno.zauberei.Compile.stdlib
import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.typeresolution.TypeResolution
import me.anno.zauberei.types.*
import me.anno.zauberei.types.StandardTypes.standardClasses
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.Types.CharType
import me.anno.zauberei.types.Types.DoubleType
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.LongType
import me.anno.zauberei.types.Types.NullableAnyType
import me.anno.zauberei.types.Types.StringType
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.NullType
import me.anno.zauberei.types.impl.UnionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// todo test all type-resolution scenarios
class TypeResolutionTest {
    companion object {
        private var ctr = 0

        fun testTypeResolution(code: String): Type {
            val testScopeName = "test${ctr++}"
            val tokens = Tokenizer(
                """
            package $testScopeName
            
            $code
        """.trimIndent(), "?"
            ).tokenize()
            ASTBuilder(tokens, root).readFileLevel()
            val testScope = root.children.first { it.name == testScopeName }
            TypeResolution.resolveTypesAndNames(testScope)
            return testScope.fields.first { it.name == "tested" }.valueType!!
        }
    }

    @Test
    fun testConstants() {
        assertEquals(BooleanType, testTypeResolution("val tested = true"))
        assertEquals(BooleanType, testTypeResolution("val tested = false"))
        assertEquals(NullType, testTypeResolution("val tested = null"))
        assertEquals(IntType, testTypeResolution("val tested = 0"))
        assertEquals(LongType, testTypeResolution("val tested = 0L"))
        assertEquals(FloatType, testTypeResolution("val tested = 0f"))
        assertEquals(FloatType, testTypeResolution("val tested = 0.0f"))
        assertEquals(DoubleType, testTypeResolution("val tested = 0d"))
        assertEquals(DoubleType, testTypeResolution("val tested = 0.0"))
        assertEquals(DoubleType, testTypeResolution("val tested = 1e3"))
        assertEquals(CharType, testTypeResolution("val tested = ' '"))
        assertEquals(StringType, testTypeResolution("val tested = \"Test 123\""))
    }

    @Test
    fun testNullableTypes() {
        assertEquals(
            UnionType(listOf(ClassType(BooleanType.clazz, null), NullType)),
            testTypeResolution("val tested: Boolean?")
        )
    }

    @Test
    fun testTypeWithGenerics() {
        assertEquals(
            ClassType(
                standardClasses["ArrayList"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            testTypeResolution("val tested: ArrayList<Int>")
        )
    }

    @Test
    fun testConstructorWithParam() {
        val intArrayType = standardClasses["IntArray"]!!
        // we need to define the constructor without any args
        val constructors = intArrayType.constructors
        if (constructors.none { it.valueParameters.size == 1 }) {
            constructors.add(
                Constructor(
                    intArrayType, listOf(
                        Parameter(
                            false, false, false,
                            "size", IntType, null, -1
                        )
                    ),
                    intArrayType.getOrCreatePrimConstructorScope(), null, null,
                    emptyList(), -1
                )
            )
        }
        assertEquals(intArrayType.typeWithoutArgs, testTypeResolution("val tested = IntArray(5)"))
    }

    @Test
    fun testConstructorsWithGenerics() {
        defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["ArrayList"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            testTypeResolution("val tested = ArrayList<Int>(8)")
        )
    }

    private fun defineArrayListConstructors() {
        val arrayListType = standardClasses["ArrayList"]!!
        if (arrayListType.typeParameters.isEmpty()) {
            arrayListType.typeParameters += Parameter(
                false, false, false,
                "X", NullableAnyType, null, -1
            )
        }

        // we need to define the constructor without any args
        val constructors = arrayListType.constructors
        if (constructors.none { it.valueParameters.isEmpty() }) {
            constructors.add(
                Constructor(
                    arrayListType, emptyList(),
                    arrayListType.getOrCreatePrimConstructorScope(), null, null,
                    emptyList(), -1
                )
            )
        }
        if (constructors.none { it.valueParameters.size == 1 }) {
            constructors.add(
                Constructor(
                    arrayListType, listOf(
                        Parameter(
                            false, false, false, "size",
                            IntType, null, -1
                        ),
                    ),
                    arrayListType.getOrCreatePrimConstructorScope(), null, null,
                    emptyList(), -1
                )
            )
        }
    }

    @Test
    fun testGenericFunction() {
        defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["List"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            testTypeResolution(
                """
                fun <V> emptyList(): List<V> = ArrayList<V>(0)
                val tested = emptyList<Int>()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testGenericsMap() {
        defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["List"]!!,
                listOf(ClassType(FloatType.clazz, null))
            ),
            testTypeResolution(
                """
                fun <V> emptyList(): List<V> = ArrayList<V>(0)
                fun <V,R> List<V>.map(map: (V) -> R): List<R> {
                    return List(size) { map(this[it]) }
                }
                val tested = emptyList<Int>().map { it + 1f }
                
                // mark Int as a class (that extends Any)
                package $stdlib
                class Int: Any() {
                    operator fun plus(other: Int): Int = native("this + other")
                    operator fun plus(other: Float): Float = native("(float)this + other")
                }
                // mark Any as a class
                class Any()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testListReduce() {
        defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["List"]!!,
                listOf(ClassType(FloatType.clazz, null))
            ),
            testTypeResolution(
                """
                fun <V> emptyList(): List<V> = ArrayList<V>(0)
                fun <V> List<V>.reduce(map: (V, V) -> V): V {
                    var element = this[0]
                    for(i in 1 until size) {
                        element = map(element, this[i])
                    }
                    return element
                }
                val tested = emptyList<Int>().reduce { a,b -> a + b }
                
                // mark Int as a class (that extends Any)
                package $stdlib
                class Int: Any() {
                    operator fun plus(other: Int): Int = native("this + other")
                    operator fun plus(other: Float): Float = native("(float)this + other")
                }
                // mark Any as a class
                class Any()
                // give some List-details
                interface List<V> {
                    val size: Int
                    operator fun get(index: Int): V
                }
            """.trimIndent()
            )
        )
    }
}