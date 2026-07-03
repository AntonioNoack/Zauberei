package me.anno.zauber.types.typeresolution

import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import me.anno.zauber.Zauber.STDLIB_NAME
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.arithmetic.UnionType.Companion.unionTypes
import org.junit.jupiter.api.Test

class GenericTest {

    @Test
    fun testTypeWithGenerics() {
        val actual = testTypeResolution("val tested: ArrayList<Int>")
        val expected = Types.ArrayList.withTypeParameter(Types.Int)
        assertEquals(expected, actual)
    }

    @Test
    fun testConstructorsWithGenerics() {
        val actual = testTypeResolution(
            """
                val tested = ArrayList<Int>(8)
                
                package zauber
                class ArrayList<Int>(initialCapacity: Int)
            """.trimIndent(), true
        )
        val expected = Types.ArrayList.withTypeParameter(Types.Int)
        assertEquals(expected, actual)
    }

    @Test
    fun testSimpleInferredGenerics() {
        assertEquals(
            Types.List.withTypeParameter(Types.Int),
            testTypeResolution(
                """
                fun <V> listOf(v: V): List<V>
                val tested = listOf(0)
            """.trimIndent()
            )
        )
        assertEquals(
            Types.List.withTypeParameter(Types.String),
            testTypeResolution(
                """
                fun <V> listOf(v: V): List<V>
                val tested = listOf("Hello World!")
            """.trimIndent()
            )
        )
    }

    @Test
    fun testInferredMapGenerics() {
        val actual = testTypeResolution(
            """
                fun <K, V> mapOf(entry: Pair<K,V>): Map<K,V>
                infix fun <F,S> F.to(other: S): Pair<F,S>
                val tested = mapOf("" to 0)
            """.trimIndent()
        )
        assertEquals(Types.Map.withTypeParameters(Types.String, Types.Int), actual)
    }

    @Test
    fun testGenericFunction() {
        val actual = testTypeResolution(
            """
                fun <V> emptyList(): List<V> = ArrayList<V>(0)
                val tested = emptyList<Int>()
            """.trimIndent()
        )
        assertEquals(Types.List.withTypeParameter(Types.Int), actual)
    }

    @Test
    fun testGenericsMap() {
        val actual = testTypeResolution(
            """
                fun <V> emptyList(): List<V>
                fun <V,R> List<V>.map(map: (V) -> R): List<R>
                val tested = emptyList<Int>().map { it + 1f }
            """.trimIndent(), true
        )
        assertEquals(Types.List.withTypeParameter(Types.Float), actual)
    }

    @Test
    fun testEmptyListAsParameter() {
        val actual = testTypeResolution(
            """
                fun <V> emptyList(): List<V>
                fun sum(list: List<Int>): Long
                val tested = sum(emptyList())               
            """.trimIndent()
        )
        assertEquals(Types.Long, actual)
    }

    @Test
    fun testTwoStackedGenericReturnTypes() {
        val actual = testTypeResolution(
            """
                infix fun <F,S> F.to(s: S): Pair<F,S>
                fun <K,V> mapOf(vararg entries: Pair<K,V>): Map<K,V>
                
                val tested = mapOf(1 to 2f)   
            """.trimIndent()
        )
        assertEquals(Types.Map.withTypeParameters(Types.Int, Types.Float), actual)
    }

    @Test
    fun testListReduceWithLambda() {
        // todo this passes alone, but fails in a group...
        val actualType = testTypeResolution(
            """
                fun <V> emptyList(): List<V>
                fun <V> List<V>.reduce(map: (V, V) -> V): V
                val tested = emptyList<Int>().reduce { a,b -> a + b }
            """.trimIndent(), true
        )
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testListReduceWithTypeMethod() {
        val actual = testTypeResolution(
            """
                fun <V> emptyList(): List<V>
                fun <V> List<V>.reduce(map: (V, V) -> V): V
                val tested = emptyList<Int>().reduce(Int::plus)
            """.trimIndent(), true
        )
        assertEquals(Types.Int, actual)
    }

    @Test
    fun testListsAreNotConfused() {
        val actual0 = testTypeResolution(
            """
                fun <V> listOf(v: V): List<V>
                fun intsToFloats(v: List<Int>): List<Float>
                
                val tested = intsToFloats(listOf(1))
            """.trimIndent()
        )
        assertEquals(Types.List.withTypeParameter(Types.Float), actual0)

        val actual1 = testTypeResolution(
            """
                fun listOf(v: Int): List<Int>
                fun intsToFloats(v: List<Int>): List<Float>
                
                val tested = intsToFloats(listOf(1))
            """.trimIndent()
        )
        assertEquals(Types.List.withTypeParameter(Types.Float), actual1)
    }

    @Test
    fun testLambdaInsideLambda() {
        // what about listOf("1,2,3").map{it.split(',').map{it.toInt()}}?
        //  can we somehow hide lambdas? I don't think so...
        val actual = testTypeResolution(
            """
                fun <V> listOf(v: V): List<V>
                fun <V,R> List<V>.map(map: (V) -> R): List<R>
                fun String.split(separator: Char): List<String>
                fun String.toInt(): Int
                
                val tested = listOf("1,2,3").map{it.split(',').map{it.toInt()}}
            """.trimIndent(), true
        )
        val listOfInt = Types.List.withTypeParameter(Types.Int)
        assertEquals(Types.List.withTypeParameter(listOfInt), actual)
    }

    @Test
    fun testMixedList() {
        val actual = testTypeResolution(
            """
                fun <V> listOf(vararg values: V): List<V>
                
                val tested = listOf("1", 1, 1f)
            """.trimIndent(), true
        )
        val mixedType = unionTypes(listOf(Types.String, Types.Int, Types.Float))
        assertEquals(Types.List.withTypeParameter(mixedType), actual)
    }

    @Test
    fun testGenericField() {
        val actual = testTypeResolution(
            """
                class A<V>(val a: V)
                
                val tested = A("").a
            """.trimIndent()
        )
        assertEquals(Types.String, actual)
    }

    @Test
    fun testGenericFieldInSuperClass() {
        val actual = testTypeResolution(
            """
                class A<V>(val a: V)
                class B<W>(a: W): A<W>(a)
                
                val tested = B("").a
            """.trimIndent()
        )
        assertEquals(Types.String, actual)
    }
}