package me.anno.zauberei.typeresolution

import me.anno.zauberei.Compile.stdlib
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.types.StandardTypes.standardClasses
import me.anno.zauberei.types.Types.FloatType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.LongType
import me.anno.zauberei.types.Types.NullableAnyType
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
        registerMapParams()
        registerPairParams()
        registerArrayParams()

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

    private fun registerMapParams() {
        val mapClass = standardClasses["Map"]!!
        if (mapClass.typeParameters.size != 2) {
            mapClass.typeParameters = listOf(
                Parameter(false, true, false, "K", NullableAnyType, null, mapClass, -1),
                Parameter(false, true, false, "V", NullableAnyType, null, mapClass, -1),
            )
        }
    }

    private fun registerPairParams() {
        val pairClass = standardClasses["Pair"]!!
        if (pairClass.typeParameters.size != 2) {
            pairClass.typeParameters = listOf(
                Parameter(false, true, false, "F", NullableAnyType, null, pairClass, -1),
                Parameter(false, true, false, "S", NullableAnyType, null, pairClass, -1),
            )
        }
    }

    private fun registerArrayParams() {
        val arrayClass = standardClasses["Pair"]!!
        if (arrayClass.typeParameters.size != 1) {
            arrayClass.typeParameters = listOf(
                Parameter(false, true, false, "V", NullableAnyType, null, arrayClass, -1),
            )
        }
    }

    @Test
    fun testTwoStackedGenericReturnTypes() {
        val mapClass = standardClasses["Map"]!!
        registerMapParams()
        registerPairParams()
        registerArrayParams()

        assertEquals(
            ClassType(
                mapClass,
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
        TypeResolutionTest.defineListParameters()

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