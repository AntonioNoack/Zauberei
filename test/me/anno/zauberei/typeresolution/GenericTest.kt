package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile.stdlib
import me.anno.zauberei.types.StandardTypes.standardClasses
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.LongType
import me.anno.zauberei.types.Types.StringType
import me.anno.zauberei.types.impl.ClassType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenericTest {

    @Test
    fun testTypeWithGenerics() {
        assertEquals(
            ClassType(
                standardClasses["ArrayList"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            TypeResolutionTest.testTypeResolution("val tested: ArrayList<Int>")
        )
    }

    @Test
    fun testConstructorsWithGenerics() {
        TypeResolutionTest.defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["ArrayList"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            TypeResolutionTest.testTypeResolution("val tested = ArrayList<Int>(8)")
        )
    }

    @Test
    fun testSimpleInferredGenerics() {
        val listClass = standardClasses["List"]!!
        assertEquals(
            ClassType(listClass, listOf(IntType)),
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> listOf(v: V): List<V>
                val tested = listOf(0)
            """.trimIndent()
            )
        )
        assertEquals(
            ClassType(listClass, listOf(StringType)),
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> listOf(v: V): List<V>
                val tested = listOf("Hello World!")
            """.trimIndent()
            )
        )
    }

    @Test
    fun testInferredMapGenerics() {
        assertEquals(
            ClassType(
                standardClasses["Map"]!!,
                listOf(StringType, IntType)
            ),
            TypeResolutionTest.testTypeResolution(
                """
                fun <K, V> mapOf(entry: Pair<K,V>): Map<K,V>
                infix fun <F,S> F.to(other: S): Pair<F,S>
                val tested = mapOf("" to 0)
            """.trimIndent()
            )
        )
    }

    @Test
    fun testGenericFunction() {
        TypeResolutionTest.defineArrayListConstructors()

        assertEquals(
            ClassType(
                standardClasses["List"]!!,
                listOf(ClassType(IntType.clazz, null))
            ),
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> emptyList(): List<V> = ArrayList<V>(0)
                val tested = emptyList<Int>()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testGenericsMap() {
        assertEquals(
            ClassType(
                standardClasses["List"]!!,
                listOf(ClassType(FloatType.clazz, null))
            ),
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> emptyList(): List<V>
                fun <V,R> List<V>.map(map: (V) -> R): List<R>
                val tested = emptyList<Int>().map { it + 1f }
                
                // mark Int as a class (that extends Any)
                package $stdlib
                class Int: Any() {
                    operator fun plus(other: Int): Int
                    operator fun plus(other: Float): Float
                }
                // mark Any as a class
                class Any()
            """.trimIndent()
            )
        )
    }

    @Test
    fun testEmptyListAsParameter() {
        assertEquals(
            LongType,
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> emptyList(): List<V>
                fun sum(list: List<Int>): Long
                val tested = sum(emptyList())               
            """.trimIndent()
            )
        )
    }

    @Test
    fun testTwoStackedGenericReturnTypes() {
        assertEquals(
            ClassType(
                standardClasses["Map"]!!,
                listOf(IntType, FloatType)
            ),
            TypeResolutionTest.testTypeResolution(
                """
                infix fun <F,S> F.to(s: S): Pair<F,S>
                fun <K,V> mapOf(vararg entries: Pair<K,V>): Map<K,V>
                
                val tested = mapOf(1 to 2f)   
            """.trimIndent()
            )
        )
    }

    @Test
    fun testListReduce() {
        assertEquals(
            IntType,
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> emptyList(): List<V>
                fun <V> List<V>.reduce(map: (V, V) -> V): V
                val tested = emptyList<Int>().reduce { a,b -> a + b }
                
                // mark Int as a class (that extends Any)
                package $stdlib
                class Int: Any() {
                    operator fun plus(other: Int): Int
                    operator fun plus(other: Float): Float
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

    @Test
    fun testListsAreNotConfused() {
        assertEquals(
            ClassType(standardClasses["List"]!!, listOf(FloatType)),
            TypeResolutionTest.testTypeResolution(
                """
                fun <V> listOf(v: V): List<V>
                fun intsToFloats(v: List<Int>): List<Float>
                
                val tested = intsToFloats(listOf(1))
            """.trimIndent()
            )
        )
        assertEquals(
            ClassType(standardClasses["List"]!!, listOf(FloatType)),
            TypeResolutionTest.testTypeResolution(
                """
                fun listOf(v: Int): List<Int>
                fun intsToFloats(v: List<Int>): List<Float>
                
                val tested = intsToFloats(listOf(1))
            """.trimIndent()
            )
        )
    }
}