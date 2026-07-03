package me.anno.support.jvm

import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import me.anno.zauber.interpreting.Instance
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class JVMInstructionTests {

    abstract class TestClass {
        abstract fun call(): Any
    }

    fun <V : TestClass> test(sample: V): Instance {
        // stdlib
        testExecute(
            """
val tested = 0 // unused

package zauber
class Array<V>(val size: Int) {
    external operator fun set(index: Int, value: Any)
    external operator fun set(index: Int, value: Byte)
    external operator fun set(index: Int, value: Int)
    fun clone(): Array<V> = copyOf()
}

package java.lang
class Object

package java.lang.reflect
import java.lang.Object
class Type {
    fun toString(): String
    fun equals(other: Object): Boolean
}
    """.trimIndent()
        )

        val sampleClass = sample.javaClass.name
            .replace('.', '/')

        return testExecute(
            """
        import java.util.ArrayList
        import ${
                sampleClass
                    .replace('/', '.')
                    .replace('$', '.')
            }
        
        val tested = ${sample.javaClass.simpleName}().call()
    """.trimIndent(), reset = false
        )
    }

    class FloatToInt : TestClass() {
        override fun call(): Int = 5f.toInt()
    }

    @Test
    fun testFloatToInt() {
        assertEquals(1, test(FloatToInt()).castToInt())
    }
}