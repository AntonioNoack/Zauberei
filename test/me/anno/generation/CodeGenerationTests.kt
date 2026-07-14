package me.anno.generation

import me.anno.compilation.MinimalCompiler
import me.anno.generation.java.JavaSourceGenerator.Companion.nativeJavaNumbers
import me.anno.utils.Half.Companion.toHalf
import me.anno.utils.NumberUtils.getMaxIntValue
import me.anno.utils.NumberUtils.getMinIntValue
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isUnsigned
import me.anno.zauber.logging.LogManager
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import java.math.BigInteger

/**
 * execution time: 2s,
 * main cost is loading Node via NVM, I think
 * */
abstract class CodeGenerationTests {

    companion object {
        private val LOGGER = LogManager.getLogger(CodeGenerationTests::class)
    }

    abstract fun registerLib()
    abstract fun generator(): MinimalCompiler

    fun testOperationOrderImpl() {
        val code = """
            val x = 5 + 12 / 4
            fun main() {
                println(x)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("8\n", printed)
    }

    fun testMethodCallImpl() {
        val code = """
            fun calc(x: Int) = x+1
            
            fun main() {
                println(calc(2))
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("3\n", printed)
    }

    fun testDataClassAndAllocationImpl() {
        val code = """
            data class Vector(val x: Int, val y: Int, val z: Int)
            
            fun main() {
                println(Vector(1, 2, 3).hashCode())
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("${(1 * 31 + 2) * 31 + 3}\n", printed)
    }

    fun testSuperAndSelfConstructorBeingCalledImpl() {
        val code = """
            open class Parent {
                init {
                    println("Parent")
                }
            }
            
            class Child: Parent() {
                init {
                    println("Child")
                }
                
                constructor(x: Int): this() {
                    println("Constructor")
                }
            }
            
            fun main() {
                Child()
                Child(1)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("Parent\nChild\nParent\nChild\nConstructor\n", printed)
    }

    fun testGenericClassImpl() {
        val code = """
            class Vector<V>(val x: V)
            
            fun main() {
                println(Vector(1).x)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("1\n", printed)
    }

    fun testValueClassFieldIsWritableImpl() {
        val code = """
            value class Vector(val x: Int, val y: Int, val z: Int)
            
            fun main() {
                var v = Vector(1,2,3)
                v.x += v.y * v.z
                println(v.x)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("7\n", printed)
    }

    fun testValueIsPassedByCopyImpl() {
        val code = """
            value class Vector(val x: Int)
            
            fun dontModify(v: Vector) {
                var w = v
                w.x = 0
            }
            
            fun main() {
                val v = Vector(1)
                dontModify(v)
                println(v.x)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("1\n", printed)
    }

    fun testSimpleBranchImpl() {
        val code = """
        fun fib(i: Int): Int {
            if (i <= 2) return i
            return fib(i-1) + fib(i-2)
        }
        fun main() {
            println(fib(7))
        }
        """.trimIndent()

        // 1, 1, 2, 3, 5, 8, 13, 21
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("21\n", printed)
    }

    fun testSimpleLoopImpl() {
        val code = """
        fun fib(i: Int): Int {
            var a = 1
            var b = 0
            var k = 0
            while (k <= i) {
                val tmp = a
                a = a + b
                b = tmp
                k++
            }
            return b
        }
        fun main() {
            println(fib(7))
        }
        """.trimIndent()

        // 1, 1, 2, 3, 5, 8, 13, 21
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("21\n", printed)
    }

    fun testIntArrayImpl() {
        // todo add this to test graph-to-class: if (i <= 2) return i
        val code = """
        fun fib(i: Int): Int {
            if (i <= 2) return i
            val v = Array<Int>(i+1)
            v[0] = 1
            v[1] = 1
            var j = 2
            while (j <= i) {
                v[j] = v[j-1] + v[j-2]
                j++
            }
            return v[i]
        }
        fun main() {
            println(fib(7))
        }
        """.trimIndent()

        // 1, 1, 2, 3, 5, 8, 13, 21
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("21\n", printed)
    }

    fun testReferenceArrayImpl() {
        val code = """
        class V(val x: Int) {
            operator fun plus(other: V): V = V(this.x + other.x)
        }
        fun fib(i: Int): Int {
            if (i <= 2) return i
            val v = Array<V>(i+1)
            v[0] = V(1)
            v[1] = V(1)
            var j = 2
            while (j <= i) {
                v[j] = v[j-1] + v[j-2]
                j++
            }
            return v[i].x
        }
        fun main() {
            println(fib(7))
        }
        """.trimIndent()

        // 1, 1, 2, 3, 5, 8, 13, 21
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("21\n", printed)
    }

    fun testClassInheritanceImpl() {
        // todo bug: why is in C++ the result type of calc() without hint resolved to Nothing??
        val code = """
            open class Parent {
                open fun calc(): Int = 1
            }
            class Child : Parent() {
                override fun calc(): Int = 2
            }
            fun main() {
                var p: Parent = Child()
                println(p.calc())
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("2\n", printed)
    }

    fun testInterfaceInheritanceImpl() {
        val code = """
            interface Parent {
                fun calc(): Int = 1
            }
            class Child : Parent {
                override fun calc(): Int = 2
            }
            fun main() {
                var p: Parent = Child()
                println(p.calc())
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("2\n", printed)
    }

    fun testNumberOverflowsImpl() {

        // todo implement and test automatic widening,
        //  half -> float -> double,
        //  byte -> short -> int -> long,
        //  ubyte -> ushort -> uint -> ulong

        // todo byte and shorts actually create ints... respect/implement that...

        val code: String = """
            fun main() {
                val byte: Byte = ${Byte.MAX_VALUE}
                val short: Short = ${Short.MAX_VALUE}
                val int: Int = ${Int.MAX_VALUE}
                val long: Long = ${Long.MAX_VALUE}
                
                println(byte + byte)
                println(short + short)
                println(int + int)
                println(long + long)
                
                val ubyte: UByte = ${UByte.MAX_VALUE}
                val ushort: UShort = ${UShort.MAX_VALUE}
                val uint: UInt = ${UInt.MAX_VALUE}
                val ulong: ULong = ${ULong.MAX_VALUE}
                println(ubyte)
                println(ushort)
                println(uint)
                println(ulong)
            }
        """.trimIndent()
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals(
            listOf(
                (Byte.MAX_VALUE * 2),
                (Short.MAX_VALUE * 2),
                (Int.MAX_VALUE * 2),
                (Long.MAX_VALUE * 2),
                UByte.MAX_VALUE,
                UShort.MAX_VALUE,
                UInt.MAX_VALUE,
                ULong.MAX_VALUE,
            ).joinToString("") { "$it\n" }, printed
        )
    }

    fun testNumberNegationImpl() {
        val numberTypes = listOf(
            Types.Byte,
            Types.Short,
            Types.Int,
            Types.Long,

            Types.UByte,
            Types.UShort,
            Types.UInt,
            Types.ULong,

            Types.Half,
            Types.Float,
            Types.Double,
        )
        val expected = listOf(
            127, -0x7f,
            0x7fff, -0x7fff,
            Int.MAX_VALUE, -Int.MAX_VALUE,
            Long.MAX_VALUE, -Long.MAX_VALUE,
            // 255 -> -255 -> 1
            UByte.MAX_VALUE, UInt.MAX_VALUE - 0xfeu,
            UShort.MAX_VALUE, UInt.MAX_VALUE - 0xfffeu,
            UInt.MAX_VALUE, 1,
            ULong.MAX_VALUE, 1,

            64992f, -64992f,
            1e38f, -1e38f,
            1e308, -1e308,
        ).map { it.toString() }
        val names = numberTypes.map { it.toString() }
        val code = numberTypes.joinToString("", "fun main() {", "}") { type ->
            val max = getBigValueForTesting(type)
            val type = type.clazz.name
            "" +
                    "val v$type: $type = $max\n" +
                    "println(+v$type)\n" +
                    "println(-v$type)\n"
        }
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEqualsNumbers(expected, names, printed)
    }

    fun testBinaryNumberOperationsImpl() {

        val numberTypes = (nativeJavaNumbers.keys - Types.Char).toList()

        val builder = StringBuilder()
        val expected = ArrayList<String>()
        val names = ArrayList<String>()

        var ctr = 0

        builder.append("fun main() {\n")

        for (type in numberTypes) {
            val typeName = type.clazz.name
            fun addCase(
                lhs: String,
                op: String,
                rhs: String,
                expectedValue: String
            ) {
                builder.append("val a$ctr: $typeName = $lhs\n")
                builder.append("val b$ctr: $typeName = $rhs\n")
                builder.append("println(a$ctr $op b$ctr)\n")

                expected.add(expectedValue)
                names.add("$type,$lhs$op$rhs")

                ctr++
            }

            addCase("2", "+", "3", "5")
            addCase("5", "-", "3", "2")
            addCase("5", "*", "3", "15")
            addCase(
                "17", "/", "3", when (type) {
                    Types.Half -> (17f / 3f).toHalf().toString()
                    Types.Float -> (17f / 3f).toString()
                    Types.Double -> (17.0 / 3.0).toString()
                    else -> "5"
                }
            )
            addCase("17", "%", "3", "2")
        }

        builder.append("}")

        val printed = generator()
            .testCompileMainAndRun(builder.toString(), ::registerLib)

        assertEqualsNumbers(expected, names, printed)
    }

    fun testNumberConversionsImpl() {
        val numberTypes = (nativeJavaNumbers.keys - Types.Char).toList()
        val builder = StringBuilder()
        val expected = ArrayList<String>()
        val names = ArrayList<String>()

        builder.append("fun main() {\n")

        var ctr = 0
        for (i in numberTypes.indices) {
            for (j in numberTypes.indices) {
                val fromType = numberTypes[i]
                val toType = numberTypes[j]

                // test all number conversions somehow...
                //  test normal numbers, and from float, also test +Inf and -Inf

                val value = getBigValueForTesting(fromType)
                builder.append("val v$ctr: ${fromType.clazz.name} = $value\n")
                builder.append("println(v${ctr}.to${toType.clazz.name}())\n")
                ctr++

                val expected0: String = when (fromType) {
                    Types.Byte -> when {
                        toType.isFloat() -> "$value.0"
                        else -> value
                    }
                    Types.UByte -> when {
                        toType.isFloat() -> "$value.0"
                        toType == Types.Byte -> "-1"
                        else -> value
                    }
                    Types.Short -> when {
                        toType == Types.Half -> "32768.0" // 7 is rounded to 8
                        toType.isFloat() -> "$value.0"
                        toType == Types.Byte -> "-1"
                        toType == Types.UByte -> "255"
                        else -> value
                    }
                    Types.UShort -> when {
                        toType == Types.Half -> "65504.0"
                        toType.isFloat() -> "$value.0"
                        toType == Types.Byte || toType == Types.Short -> "-1"
                        toType == Types.UByte -> "255"
                        else -> value
                    }
                    Types.Int -> when (toType) {
                        Types.Half -> value.toFloat().toHalf().toString()
                        Types.Float -> value.toFloat().toString()
                        Types.Double -> value.toDouble().toString()
                        Types.Byte, Types.Short -> "-1"
                        Types.UByte, Types.UShort ->
                            getMaxValueForTesting(toType)
                        else -> value
                    }
                    Types.UInt -> when (toType) {
                        Types.Half -> value.toFloat().toHalf().toString()
                        Types.Float -> value.toFloat().toString()
                        Types.Double -> value.toDouble().toString()
                        Types.Byte, Types.Short, Types.Int -> "-1"
                        Types.UByte, Types.UShort ->
                            getMaxValueForTesting(toType)
                        else -> value
                    }
                    Types.Long -> when (toType) {
                        Types.Half -> value.toFloat().toHalf().toString()
                        Types.Float -> value.toFloat().toString()
                        Types.Double -> value.toDouble().toString()
                        Types.Byte, Types.Short, Types.Int -> "-1"
                        Types.UByte, Types.UShort, Types.UInt ->
                            getMaxValueForTesting(toType)
                        else -> value
                    }
                    Types.ULong -> when (toType) {
                        Types.Half -> value.toFloat().toHalf().toString()
                        Types.Float -> value.toFloat().toString()
                        Types.Double -> value.toDouble().toString()
                        Types.Byte, Types.Short, Types.Int, Types.Long -> "-1"
                        else -> getMaxValueForTesting(toType)
                    }
                    Types.Half -> when (toType) {
                        Types.Half, Types.Float, Types.Double -> value
                        // all these types fit:
                        Types.UShort, Types.Int, Types.UInt, Types.ULong, Types.Long -> "64992"
                        // Byte, UByte, Short are max-value
                        else -> getMaxValueForTesting(toType)
                    }
                    Types.Float -> when (toType) {
                        Types.Float -> value
                        Types.Double -> value.toFloat().toDouble().toString()
                        else -> getMaxValueForTesting(toType)
                    }
                    Types.Double -> when (toType) {
                        Types.Double -> value
                        else -> getMaxValueForTesting(toType)
                    }
                    else -> throw NotImplementedError("Add $fromType -> $toType")
                }
                expected.add(expected0.replace("h", ".0"))
                names.add("$fromType->$toType")

                if (fromType.isFloat()) {
                    builder.append("val v$ctr: ${fromType.clazz.name} = 1e1000\n")
                    builder.append("println(v${ctr}.to${toType.clazz.name}())\n")
                    expected.add(getMaxValueForTesting(toType))
                    names.add("$fromType->$toType/Inf")
                    ctr++

                    builder.append("val v$ctr: ${fromType.clazz.name} = -1e1000\n")
                    builder.append("println(v${ctr}.to${toType.clazz.name}())\n")
                    expected.add(getMinValueForTesting(toType))
                    names.add("$fromType->$toType/-Inf")
                    ctr++
                }
            }
        }

        builder.append("}")

        val printed = generator()
            .testCompileMainAndRun(builder.toString(), ::registerLib)
        assertEqualsNumbers(expected, names, printed)
    }

    fun assertEqualsNumbers(expected: List<String>, names: List<String>, printed: String) {
        val actual = printed.lines()
            .filter { it.isNotEmpty() }
        assertEquals(expected.size, actual.size)
        var mismatches = 0
        for (i in expected.indices) {
            try {
                assertEqualsNumber(expected[i], actual[i])
            } catch (e: Throwable) {
                var message = (e.message ?: "")
                if (message.endsWith(": ")) message = message.dropLast(2)
                LOGGER.error("${e.javaClass.simpleName}: $message for ${names[i]}")
                mismatches++
            }
        }
        assertEquals(0, mismatches) { "Must have zero mismatches" }
    }

    fun assertEqualsNumber(expected: String, actual: String) {
        if ('I' in expected || 'N' in expected) {
            assertEquals(expected, actual)
        } else if ('.' in expected || '.' in actual) {
            assertEquals(expected.toDouble(), actual.toDouble())
        } else {
            assertEquals(BigInteger(expected), BigInteger(actual))
        }
    }

    fun getMaxValueForTesting(type: Type): String {
        return when (type) {
            // if comparison is signed for unsigned numbers,
            // UNSIGNED.MAX_VALUE is -1, and would give the wrong answer
            Types.Half, Types.Float, Types.Double -> "Infinity"
            else -> getMaxIntValue(type).toString()
        }
    }

    fun getMinValueForTesting(type: Type): String {
        return when (type) {
            // if comparison is signed for unsigned numbers,
            // UNSIGNED.MAX_VALUE is -1, and would give the wrong answer
            Types.Half, Types.Float, Types.Double -> "-Infinity"
            else -> getMinIntValue(type).toString()
        }
    }

    fun getBigValueForTesting(type: Type): String {
        return when (type) {
            Types.Half -> "64992h"
            Types.Float -> "1.0E38"
            Types.Double -> "1.0E308"
            else -> getMaxValueForTesting(type)
        }
    }

    fun testNumberComparisonsImpl() {
        // if comparison is signed for unsigned numbers,
        // UNSIGNED.MAX_VALUE is -1, and would give the wrong answer
        var ctr = 0
        val numberTypes = nativeJavaNumbers.keys
        val code = numberTypes.joinToString("", "fun main() {", "}") { type ->
            val min = when (type) {
                Types.Char -> "' '"
                Types.Float -> "0f"
                Types.Double -> "0.0"
                else -> "0"
            }
            val max = getBigValueForTesting(type)
            val type = type.clazz.name
            "" +
                    "val v${ctr++}: $type = $min\n" +
                    "val v${ctr++}: $type = $max\n" +
                    "println(if(v${ctr - 2} < v${ctr - 1}) 1 else 0)\n"
        }
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("1\n".repeat(numberTypes.size), printed)
    }

    fun testNonNumberComparisonsImpl() {
        val code = """
            class Vector(val x: Int, val y: Int) {
                operator fun compareTo(other: Vector): Int {
                    val dx = x.compareTo(other.x)
                    if (dx == 0) return y.compareTo(other.y)
                    return dx
                }
            }
            fun main() {
                val condition = Vector(1, 2) > Vector(1, 3)
                println(if(condition) 17 else 12)
            }
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("12\n", printed)
    }

    private fun Int.repeat4(n: Int): Long {
        var result = 0L
        val value = this.toLong()
        repeat(n) { shift ->
            result += value.shl(4 * shift)
        }
        return result
    }

    /**
     * test all logical operators:
     * and, or, xor, inverse, shl, shr, ushr, rotateLeft, rotateRight
     * */
    fun testLogicalOperatorsImpl() {

        val intTypes = nativeJavaNumbers.keys.filter { it != Types.Char && !it.isFloat() }
        val expected = ArrayList<String>()
        val names = ArrayList<String>()

        val code = intTypes.withIndex().joinToString("", "fun main() {", "}") { (ctr, type) ->

            val numBits = type.getNumBits()
            val repeats = numBits.shr(2)

            val shift = 5

            val seqA = "0101".repeat(repeats)
            val seqB = "0011".repeat(repeats)

            val seqAn = 0b0101.repeat4(repeats)

            val andExpected = 0b0001.repeat4(repeats)
            val orExpected = 0b0111.repeat4(repeats)
            val xorExpected = 0b0110.repeat4(repeats)
            val invExpected = 0b1010.repeat4(repeats)

            val mask =
                if (numBits == 64) -1L // this op would do nothing, because only the last 6 bits of the shift are respected
                else (1L shl numBits) - 1L

            val sh = 64 - numBits

            val shlExpected = seqAn.shl(shift).and(mask)
            val signedInvExpected = invExpected.shxs(sh)
            val shrExpected =
                if (type.isUnsigned()) invExpected ushr shift
                else signedInvExpected shr shift

            val ushrExpected = invExpected.ushr(shift)
            val rotlExpected = (seqAn.shl(shift) or seqAn.ushr(numBits - shift)).and(mask)
            val rotrExpected = (seqAn.ushr(shift) or seqAn.shl(numBits - shift)).and(mask)

            val tn = type.toString()
            if (type.isUnsigned()) {
                expected.add(andExpected.toULong().toString())
                expected.add(orExpected.toULong().toString())
                expected.add(xorExpected.toULong().toString())
                expected.add(invExpected.toULong().toString())

                if (numBits > 16) {
                    expected.add(shlExpected.toULong().toString())
                    expected.add(shrExpected.toULong().toString())
                    expected.add(ushrExpected.toULong().toString())
                    expected.add(rotlExpected.toULong().toString())
                    expected.add(rotrExpected.toULong().toString())
                }
            } else {
                expected.add(andExpected.toString())
                expected.add(orExpected.toString())
                expected.add(xorExpected.toString())
                expected.add(signedInvExpected.toString())

                if (numBits > 16) {
                    expected.add(shlExpected.shxs(sh).toString())
                    expected.add(shrExpected.shxs(sh).toString())
                    expected.add(ushrExpected.shxu(sh).toString())
                    expected.add(rotlExpected.shxs(sh).toString())
                    expected.add(rotrExpected.shxs(sh).toString())
                }
            }

            names.add("$tn.and")
            names.add("$tn.or")
            names.add("$tn.xor")
            names.add("$tn.inv")
            if (numBits > 16) {
                names.add("$tn.shl")
                names.add("$tn.shr")
                names.add("$tn.ushr")
                names.add("$tn.rotl")
                names.add("$tn.rotr")
            }

            val typeName = type.clazz.name
            "" +
                    "val a${ctr}: $typeName = 0b$seqA\n" +
                    "val b${ctr}: $typeName = 0b$seqB\n" +
                    "println(a${ctr} and b${ctr})\n" +
                    "println(a${ctr}  or b${ctr})\n" +
                    "println(a${ctr} xor b${ctr})\n" +
                    "println(a${ctr}.inv())\n" +
                    if (numBits > 16) (
                            "println(a${ctr} shl $shift)\n" +
                                    "println(a${ctr}.inv() shr $shift)\n" +
                                    "println(a${ctr}.inv() ushr $shift)\n" +
                                    "println(a${ctr}.rotateLeft($shift))\n" +
                                    "println(a${ctr}.rotateRight($shift))\n") else ""
        }
        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEqualsNumbers(expected, names, printed)
    }

    private fun Long.shxs(sh: Int): Long {
        return shl(sh).shr(sh)
    }

    private fun Long.shxu(sh: Int): Long {
        return shl(sh).ushr(sh)
    }

    fun testInstanceOfImpl() {
        val code = """
            fun getInt(v: Any): Int {
                return if (v is Int) v else 3
            }
            fun main() {
                println(getInt(2))
            }
            package zauber
            external fun unreachable(): Nothing
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("2\n", printed)
    }

    fun testStringOpsImpl() {
        val code = """
            fun main() {
                println("Hello World!")
                println("  a  ".trim())
                println("".trim())
            }
            package zauber
            external class Char(val content: Char) {
                fun isWhitespace() = this in " \t\r\n"
                external fun equals(other: Char): Boolean
            }
            external class Ref<T>
        """.trimIndent()

        val printed = generator()
            .testCompileMainAndRun(code, ::registerLib)
        assertEquals("Hello World!\na\n\n", printed)
    }
}