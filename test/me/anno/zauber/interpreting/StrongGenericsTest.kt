package me.anno.zauber.interpreting

import me.anno.utils.assertEquals
import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import org.junit.jupiter.api.Test

// do we want to use need-to-use ::class?
//  benefits:
//  - class is clearly visible as such
//  - object vs class is clear
//  negatives:
//  - often extra writing for what...
class StrongGenericsTest {

    @Test
    fun testGenericsHaveNameNativeType() {
        // todo what is that weird error?
        //  Unresolved field for field type: (MemberNameExpression) zauber.Nothing dot name in test0.$call_o2.$body_n1

        // todo when we don't define the return-type for call, we somehow get an error :(
        val code = """
            fun <V> call(v: V): String = (V::class as ClassType<*>).name
            val tested = call(0)
        """.trimIndent()
        val value = testExecute(code)
        assertEquals("Int", value.castToString())
    }

    @Test
    fun testValuesHaveNameNativeType() {
        val code = """
            fun <V> call(v: V) = v::class.name
            val tested = call(0)
        """.trimIndent()
        val value = testExecute(code)
        assertEquals("Int", value.castToString())
    }

    @Test
    fun testGenericsHaveNameReferenceType() {
        val code = """
            fun <V> call(v: V): String = (V::class as ClassType<*>).name
            val tested = call("Hello")
        """.trimIndent()
        val value = testExecute(code)
        assertEquals("String", value.castToString())
    }

    @Test
    fun testGenericsAsCondition() {
        val trueCode = """
            fun <V> call(v: V): Boolean = V::class.isSubTypeOf(Int::class)
            val tested = call(0)
        """.trimIndent()
        val valueT = testExecute(trueCode)
        assertEquals(true, valueT.castToBool())
        val falseCode = """
            fun <V> call(v: V): Boolean = V::class.isSubTypeOf(Int::class)
            val tested = call(0f)
        """.trimIndent()
        val valueF = testExecute(falseCode)
        assertEquals(false, valueF.castToBool())
    }

    @Test
    fun testValuesAsCondition() {
        val trueCode = """
            fun <V> call(v: V): Boolean = v::class.isSubTypeOf(Int::class)
            val tested = call(0)
        """.trimIndent()
        val valueT = testExecute(trueCode)
        assertEquals(true, valueT.castToBool())
        val falseCode = """
            fun <V> call(v: V): Boolean = v::class.isSubTypeOf(Int::class)
            val tested = call(0f)
        """.trimIndent()
        val valueF = testExecute(falseCode)
        assertEquals(false, valueF.castToBool())
    }
}