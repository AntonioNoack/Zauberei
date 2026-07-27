package me.anno.zauber.interpreting

import me.anno.utils.Half
import me.anno.utils.Half.Companion.toHalf
import me.anno.utils.Maths.clamp
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.simple.ASTSimplifier.nativeNumbers
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.RuntimeCreate.createByte
import me.anno.zauber.interpreting.RuntimeCreate.createChar
import me.anno.zauber.interpreting.RuntimeCreate.createDouble
import me.anno.zauber.interpreting.RuntimeCreate.createFloat
import me.anno.zauber.interpreting.RuntimeCreate.createHalf
import me.anno.zauber.interpreting.RuntimeCreate.createInt
import me.anno.zauber.interpreting.RuntimeCreate.createLong
import me.anno.zauber.interpreting.RuntimeCreate.createShort
import me.anno.zauber.interpreting.RuntimeCreate.createString
import me.anno.zauber.interpreting.RuntimeCreate.createUByte
import me.anno.zauber.interpreting.RuntimeCreate.createUInt
import me.anno.zauber.interpreting.RuntimeCreate.createULong
import me.anno.zauber.interpreting.RuntimeCreate.createUShort
import me.anno.zauber.typeresolution.Inheritance
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or
import kotlin.experimental.xor

/**
 * Standard library implementation for interpreter / compile-time execution
 * */
object Stdlib {

    inline fun Runtime.registerBinaryMethod(
        type: ClassType,
        name: String,
        crossinline calc: (Instance, Instance) -> Instance
    ) {
        registerBinaryMethod(type, type, name, calc)
    }

    inline fun Runtime.registerBinaryMethod(
        type: ClassType, argType: ClassType,
        name: String, crossinline calc: (Instance, Instance) -> Instance
    ) {
        register(type.clazz, name, listOf(argType)) { self, params ->
            calc(self, params[0])
        }
    }

    fun registerPrintln() {
        // these are optional:
        runtime.register(langScope, "println", listOf(Types.String)) { _, params ->
            runPrintln(params[0].castToString())
        }
        for (type in nativeNumbers) {
            runtime.register(langScope, "println", listOf(type)) { _, params ->
                val value = params[0]
                assertEquals(value.clazz.type, type) {
                    "Mismatch in printed vs expected type"
                }
                check(value.rawValue != null)
                runPrintln(value.rawValue.toString())
            }
        }
        runtime.register(langScope, "flushConsole", emptyList()) { _, _ ->
            // todo printing to the real console should be optional
            // todo clearing the buffer can be optional then, too,
            //  and then we don't need the duplicated 'printed' field in Runtime
            val instance = runtime.getObjectInstance(langScope)
            val printed = instance["printed"]
            val printedType = (printed.clazz.type as ClassType).clazz
            // call toString(), print it, and then call clear()
            val toStringMethod = printedType.methods
                .firstOrNull { it.name == "toString" && it.valueParameters.isEmpty() }
                ?: error("Missing fun $printedType.toString()")
            val toStringSpec = Specialization.fromSimple(toStringMethod.scope)
            val clearMethod = printedType.methods
                .firstOrNull { it.name == "clear" && it.valueParameters.isEmpty() }
                ?: error("Missing fun $printedType.clear()")
            val clearSpec = Specialization.fromSimple(clearMethod.scope)
            val value = runtime.executeCall(
                printed, null,
                toStringSpec, emptyList(), -1
            ).finish()
            runPrint(value.castToString())
            runtime.executeCall(
                printed, null,
                clearSpec, emptyList(), -1
            ).finish()
        }
    }

    private fun runPrintln(content: String): Instance {
        val rt = runtime
        rt.printed.append(content).append('\n')
        println(content)
        return rt.getUnit()
    }

    private fun runPrint(content: String): Instance {
        val rt = runtime
        rt.printed.append(content)
        print(content)
        return rt.getUnit()
    }

    private fun checkIsArray(self: Instance) {
        assertEquals(Types.Array.clazz, (self.clazz.type as? ClassType)?.clazz) {
            "ClassCastException: $self is not an array"
        }
    }

    fun registerArrayAccess() {
        runtime.register(
            Types.Array.clazz, "get",
            listOf(Types.Int)
        ) { self, (index0) ->
            checkIsArray(self)
            val index = index0.castToInt()
            val rt = runtime
            when (val content = self.rawValue) {
                is Array<*> -> content[index] as Instance
                is BooleanArray -> rt.getBool(content[index])
                is ByteArray -> rt.createByte(content[index])
                is ShortArray -> rt.createShort(content[index])
                is CharArray -> rt.createChar(content[index])
                is IntArray -> rt.createInt(content[index])
                is LongArray -> rt.createLong(content[index])
                is FloatArray -> rt.createFloat(content[index])
                is DoubleArray -> rt.createDouble(content[index])
                null -> error("Missing array content in $self")
                else -> error("Unknown array content: ${content.javaClass.simpleName}")
            }
        }
        runtime.register(
            Types.Array.clazz, "set",
            listOf(Types.Int, GenericType(Types.Array.clazz, "V"))
        ) { self, (index, value) ->
            arraySet(self, index, value)
            runtime.getUnit()
        }
        // todo why is this needed for "testListOfLambdasTotallyExplicit"?
        runtime.register(
            Types.Array.clazz, "set",
            listOf(Types.Int, Types.NullableAny)
        ) { self, (index, value) ->
            arraySet(self, index, value)
            runtime.getUnit()
        }
    }

    private fun arraySet(self: Instance, index: Instance, value: Instance) {
        checkIsArray(self)
        val index1 = index.castToInt()
        @Suppress("UNCHECKED_CAST")
        when (val content = self.rawValue) {
            is Array<*> -> (content as Array<Instance>)[index1] = value
            is BooleanArray -> content[index1] = value.castToBool()
            is ByteArray -> content[index1] = value.castToByte()
            is ShortArray -> content[index1] = value.castToShort()
            is CharArray -> content[index1] = value.castToChar()
            is IntArray -> content[index1] = value.castToInt()
            is LongArray -> content[index1] = value.castToLong()
            is FloatArray -> content[index1] = value.castToFloat()
            is DoubleArray -> content[index1] = value.castToDouble()
            null -> error("Missing array content")
            else -> error("Unknown array content: ${content.javaClass.simpleName}")
        }
    }

    fun registerSmallIntMethods() {
        val rt = runtime
        rt.registerUnaryMethod(Types.Byte, "toChar") { self ->
            rt.createChar(self.castToByte().toInt().and(0xff).toChar())
        }
    }

    fun registerByteMethods() {
        val rt = runtime
        rt.registerByteIntMethod("plus", Byte::plus)
        rt.registerByteIntMethod("minus", Byte::minus)
        rt.registerByteIntMethod("times", Byte::times)
        rt.registerByteIntMethod("div", Byte::div)
        rt.registerByteIntMethod("rem", Byte::rem)
        rt.registerBinaryByteMethod2("and", Byte::and)
        rt.registerBinaryByteMethod2("or", Byte::or)
        rt.registerBinaryByteMethod2("xor", Byte::xor)
        rt.registerBinaryByteMethod("compareTo", Byte::compareTo)
        rt.register(Types.Byte, "inv", emptyList()) { self, _ ->
            rt.createByte(self.castToByte().inv())
        }
    }

    fun registerUByteMethods() {
        val rt = runtime
        rt.registerUByteUIntMethod("plus", UByte::plus)
        rt.registerUByteUIntMethod("minus", UByte::minus)
        rt.registerUByteUIntMethod("times", UByte::times)
        rt.registerUByteUIntMethod("div", UByte::div)
        rt.registerUByteUIntMethod("rem", UByte::rem)
        rt.registerBinaryUByteMethod2("and", UByte::and)
        rt.registerBinaryUByteMethod2("or", UByte::or)
        rt.registerBinaryUByteMethod2("xor", UByte::xor)
        rt.registerBinaryMethod(Types.UByte, "compareTo") { a, b ->
            rt.createInt(a.castToUByte().compareTo(b.castToUByte()))
        }
        rt.register(Types.UByte, "inv", emptyList()) { self, _ ->
            rt.createUByte(self.castToUByte().inv())
        }
    }

    fun registerShortMethods() {
        val rt = runtime
        rt.registerShortIntMethod("plus", Short::plus)
        rt.registerShortIntMethod("minus", Short::minus)
        rt.registerShortIntMethod("times", Short::times)
        rt.registerShortIntMethod("div", Short::div)
        rt.registerShortIntMethod("rem", Short::rem)
        rt.registerBinaryShortMethod2("and", Short::and)
        rt.registerBinaryShortMethod2("or", Short::or)
        rt.registerBinaryShortMethod2("xor", Short::xor)
        rt.registerBinaryShortMethod("compareTo", Short::compareTo)
        rt.register(Types.Short, "inv", emptyList()) { self, _ ->
            rt.createShort(self.castToShort().inv())
        }
    }

    fun registerUShortMethods() {
        val rt = runtime
        rt.registerUShortUIntMethod("plus", UShort::plus)
        rt.registerUShortUIntMethod("minus", UShort::minus)
        rt.registerUShortUIntMethod("times", UShort::times)
        rt.registerUShortUIntMethod("div", UShort::div)
        rt.registerUShortUIntMethod("rem", UShort::rem)
        rt.registerBinaryUShortMethod2("and", UShort::and)
        rt.registerBinaryUShortMethod2("or", UShort::or)
        rt.registerBinaryUShortMethod2("xor", UShort::xor)
        rt.registerBinaryMethod(Types.UShort, "compareTo") { a, b ->
            rt.createInt(a.castToUShort().compareTo(b.castToUShort()))
        }
        rt.register(Types.UShort, "inv", emptyList()) { self, _ ->
            rt.createUShort(self.castToUShort().inv())
        }
    }

    fun registerIntMethods() {
        val rt = runtime
        rt.registerBinaryIntMethod("plus", Int::plus)
        rt.registerBinaryIntMethod("minus", Int::minus)
        rt.registerBinaryIntMethod("times", Int::times)
        rt.registerBinaryIntMethod("div", Int::div)
        rt.registerBinaryIntMethod("rem", Int::rem)
        rt.registerBinaryIntMethod("shl", Int::shl)
        rt.registerBinaryIntMethod("shr", Int::shr)
        rt.registerBinaryIntMethod("ushr", Int::ushr)
        rt.registerBinaryIntMethod("compareTo", Int::compareTo)
        rt.registerBinaryIntMethod("and", Int::and)
        rt.registerBinaryIntMethod("or", Int::or)
        rt.registerBinaryIntMethod("xor", Int::xor)
        rt.registerBinaryIntMethod("rotateLeft", Int::rotateLeft)
        rt.registerBinaryIntMethod("rotateRight", Int::rotateRight)
        rt.register(Types.Int, "inv", emptyList()) { self, _ ->
            rt.createInt(self.castToInt().inv())
        }
    }

    fun registerUIntMethods() {
        val rt = runtime
        rt.registerBinaryUIntMethod("plus", UInt::plus)
        rt.registerBinaryUIntMethod("minus", UInt::minus)
        rt.registerBinaryUIntMethod("times", UInt::times)
        rt.registerBinaryUIntMethod("div", UInt::div)
        rt.registerBinaryUIntMethod("rem", UInt::rem)
        rt.registerBinaryUIntMethod("and", UInt::and)
        rt.registerBinaryUIntMethod("or", UInt::or)
        rt.registerBinaryUIntMethod("xor", UInt::xor)
        rt.registerBinaryUIntMethod2("shl", UInt::shl)
        rt.registerBinaryUIntMethod2("shr", UInt::shr)
        rt.registerBinaryUIntMethod2("ushr", UInt::shr)
        rt.registerBinaryUIntMethod2("rotateLeft", UInt::rotateLeft)
        rt.registerBinaryUIntMethod2("rotateRight", UInt::rotateRight)
        rt.registerBinaryMethod(Types.UInt, "compareTo") { a, b ->
            rt.createInt(a.castToUInt().compareTo(b.castToUInt()))
        }
        rt.register(Types.UInt, "inv", emptyList()) { self, _ ->
            rt.createUInt(self.castToUInt().inv())
        }
    }

    fun registerLongMethods() {
        val rt = runtime
        rt.registerBinaryLongMethod("plus", Long::plus)
        rt.registerBinaryLongMethod("minus", Long::minus)
        rt.registerBinaryLongMethod("times", Long::times)
        rt.registerBinaryLongMethod("div", Long::div)
        rt.registerBinaryLongMethod("rem", Long::rem)
        rt.registerBinaryLongMethod("and", Long::and)
        rt.registerBinaryLongMethod("or", Long::or)
        rt.registerBinaryLongMethod("xor", Long::xor)
        rt.registerBinaryLongMethod2("shl", Long::shl)
        rt.registerBinaryLongMethod2("shr", Long::shr)
        rt.registerBinaryLongMethod2("ushr", Long::ushr)
        rt.registerBinaryLongMethod2("rotateLeft", Long::rotateLeft)
        rt.registerBinaryLongMethod2("rotateRight", Long::rotateRight)
        rt.registerBinaryMethod(Types.Long, "compareTo") { a, b ->
            rt.createInt(a.castToLong().compareTo(b.castToLong()))
        }
        rt.register(Types.Long, "inv", emptyList()) { self, _ ->
            rt.createLong(self.castToLong().inv())
        }
    }

    fun registerULongMethods() {
        val rt = runtime
        rt.registerBinaryULongMethod("plus", ULong::plus)
        rt.registerBinaryULongMethod("minus", ULong::minus)
        rt.registerBinaryULongMethod("times", ULong::times)
        rt.registerBinaryULongMethod("div", ULong::div)
        rt.registerBinaryULongMethod("rem", ULong::rem)
        rt.registerBinaryULongMethod("and", ULong::and)
        rt.registerBinaryULongMethod("or", ULong::or)
        rt.registerBinaryULongMethod("xor", ULong::xor)
        rt.registerBinaryULongMethod2("shl", ULong::shl)
        rt.registerBinaryULongMethod2("shr", ULong::shr)
        rt.registerBinaryULongMethod2("ushr", ULong::shr)
        rt.registerBinaryULongMethod2("rotateLeft", ULong::rotateLeft)
        rt.registerBinaryULongMethod2("rotateRight", ULong::rotateRight)
        rt.registerBinaryMethod(Types.ULong, "compareTo") { a, b ->
            rt.createInt(a.castToULong().compareTo(b.castToULong()))
        }
        rt.register(Types.ULong, "inv", emptyList()) { self, _ ->
            rt.createULong(self.castToULong().inv())
        }
    }

    fun registerHalfMethods() {
        val rt = runtime
        rt.registerBinaryHalfMethod("plus", Half::plus)
        rt.registerBinaryHalfMethod("minus", Half::minus)
        rt.registerBinaryHalfMethod("times", Half::times)
        rt.registerBinaryHalfMethod("div", Half::div)
        rt.registerBinaryHalfMethod("rem", Half::rem)
        rt.registerBinaryMethod(Types.Half, "compareTo") { a, b ->
            rt.createInt(a.castToHalf().compareTo(b.castToHalf()))
        }
        rt.registerUnaryMethod(Types.Half, "toBits") { value ->
            rt.createShort(value.castToHalf().binary)
        }
    }

    fun registerFloatMethods() {
        val rt = runtime
        rt.registerBinaryFloatMethod("plus", Float::plus)
        rt.registerBinaryFloatMethod("minus", Float::minus)
        rt.registerBinaryFloatMethod("times", Float::times)
        rt.registerBinaryFloatMethod("div", Float::div)
        rt.registerBinaryFloatMethod("rem", Float::rem)
        rt.registerBinaryMethod(Types.Float, "compareTo") { a, b ->
            rt.createInt(a.castToFloat().compareTo(b.castToFloat()))
        }
        rt.registerUnaryMethod(Types.Float, "toBits") { value ->
            rt.createInt(value.castToFloat().toRawBits())
        }
    }

    fun registerDoubleMethods() {
        val rt = runtime
        rt.registerBinaryDoubleMethod("plus", Double::plus)
        rt.registerBinaryDoubleMethod("minus", Double::minus)
        rt.registerBinaryDoubleMethod("times", Double::times)
        rt.registerBinaryDoubleMethod("div", Double::div)
        rt.registerBinaryDoubleMethod("rem", Double::rem)
        rt.registerBinaryMethod(Types.Double, "compareTo") { a, b ->
            rt.createInt(a.castToDouble().compareTo(b.castToDouble()))
        }
        rt.registerUnaryMethod(Types.Double, "toBits") { value ->
            rt.createLong(value.castToDouble().toRawBits())
        }
    }

    fun registerNumberConversions() {
        val types = nativeNumbers
        val rt = runtime
        for (i in types.indices) {
            for (j in types.indices) {
                val fromType = types[i]
                val toType = types[j]

                rt.registerUnaryMethod(fromType, "to${toType.clazz.name}") { from ->
                    if (fromType.isFloat()) {
                        val fromValue = getFloatValue(from, fromType)
                        rt.createNumberFromFloat(fromValue, toType)
                    } else {
                        val fromValue = getIntValue(from, fromType)
                        rt.createNumberFromInt(fromValue, fromType, toType)
                    }
                }
            }
        }
    }

    private fun getFloatValue(from: Instance, fromType: Type): Double {
        return when (fromType) {
            Types.Half -> from.castToHalf().toDouble()
            Types.Float -> from.castToFloat().toDouble()
            Types.Double -> from.castToDouble()
            else -> throw NotImplementedError()
        }
    }

    private fun getIntValue(from: Instance, fromType: Type): Long {
        return when (fromType) {
            Types.Char -> from.castToChar().code.toLong()
            Types.Byte -> from.castToByte().toLong()
            Types.UByte -> from.castToUByte().toLong()
            Types.Short -> from.castToShort().toLong()
            Types.UShort -> from.castToUShort().toLong()
            Types.Int -> from.castToInt().toLong()
            Types.UInt -> from.castToUInt().toLong()
            Types.Long -> from.castToLong()
            Types.ULong -> from.castToULong().toLong()
            else -> throw NotImplementedError()
        }
    }

    private fun Runtime.createNumberFromFloat(from: Double, toType: Type): Instance {
        return when (toType) {
            Types.Half -> createHalf(from.toHalf())
            Types.Float -> createFloat(from.toFloat())
            Types.Double -> createDouble(from)

            Types.Char -> createChar(from.toInt().toChar())
            Types.Byte -> createByte(clamp(from.toInt(), -128, 127).toByte())
            Types.UByte -> createUByte(clamp(from.toInt(), 0, 255).toUByte())
            Types.Short -> createShort(clamp(from.toInt(), -0x8000, 0x7fff).toShort())
            Types.UShort -> createUShort(clamp(from.toInt(), 0, 0xffff).toUShort())
            Types.Int -> createInt(from.toInt())
            Types.UInt -> createUInt(from.toUInt())
            Types.Long -> createLong(from.toLong())
            Types.ULong -> createULong(from.toULong())
            else -> throw NotImplementedError("Create $toType from Double")
        }
    }

    private fun Runtime.createNumberFromInt(from: Long, fromType: Type, toType: Type): Instance {
        return when (toType) {
            Types.Half -> createHalf(
                if (fromType == Types.ULong) from.toULong().toFloat().toHalf()
                else from.toFloat().toHalf()
            )
            Types.Float -> createFloat(if (fromType == Types.ULong) from.toULong().toFloat() else from.toFloat())
            Types.Double -> createDouble(if (fromType == Types.ULong) from.toULong().toDouble() else from.toDouble())

            Types.Char -> createChar(from.toInt().toChar())
            Types.Byte -> createByte(from.toByte())
            Types.UByte -> createUByte(from.toUByte())
            Types.Short -> createShort(from.toShort())
            Types.UShort -> createUShort(from.toUShort())
            Types.Int -> createInt(from.toInt())
            Types.UInt -> createUInt(from.toUInt())
            Types.Long -> createLong(from)
            Types.ULong -> createULong(from.toULong())
            else -> throw NotImplementedError("Create $toType from Long")
        }
    }

    fun registerStringMethods() {
        val rt = runtime
        rt.registerBinaryMethod(Types.String, "plus") { a, b ->
            rt.createString(a.castToString() + b.castToString())
        }
        rt.registerBinaryMethod(Types.String, "split") { content, separator ->
            val contentI = content.castToString()
            val separator = separator.castToString()
            val parts0 = contentI.split(separator)
            val parts1 = Array(parts0.size) { rt.createString(parts0[it]) }
            content.clazz.createArray(parts1)
        }
        rt.register(Types.Any.clazz, "toString", emptyList()) { instance, _ ->
            val str = when (instance.clazz.type) {
                Types.Byte -> instance.castToByte().toString()
                Types.UByte -> instance.castToUByte().toString()
                Types.Short -> instance.castToShort().toString()
                Types.UShort -> instance.castToUShort().toString()
                Types.Char -> instance.castToChar().toString()
                Types.Int -> instance.castToInt().toString()
                Types.UInt -> instance.castToUInt().toString()
                Types.Long -> instance.castToLong().toString()
                Types.ULong -> instance.castToULong().toString()
                Types.Half -> instance.castToHalf().toString()
                Types.Float -> instance.castToFloat().toString()
                Types.Double -> instance.castToDouble().toString()
                Types.Boolean -> instance.castToBool().toString()
                Types.String -> instance.castToString()
                Types.Unit -> "Unit"
                else -> "${(instance.clazz.type)}@${instance.id}"
            }
            rt.createString(str)
        }
    }

    fun registerTypeMethods() {
        val rt = runtime
        rt.register(Types.TypeT.clazz, "isSubTypeOf", listOf(Types.TypeT)) { type, (otherType) ->
            rt.getBool(Inheritance.isSubTypeOf(expectedType = otherType.castToType(), actualType = type.castToType()))
        }
        rt.register(Types.ClassType.clazz, "sizeof", emptyList()) { self, _ ->
            val type = self.rawValue as ClassType
            check(type in nativeNumbers) {
                "Sizeof not yet implemented for non-numbers: $type"
            }
            val numBytes = type.getNumBits().shr(3)
            rt.createInt(numBytes)
        }
    }

    fun registerAllMethods() {
        registerSmallIntMethods()

        registerByteMethods()
        registerUByteMethods()

        registerShortMethods()
        registerUShortMethods()

        registerIntMethods()
        registerUIntMethods()

        registerLongMethods()
        registerULongMethods()

        registerHalfMethods()
        registerFloatMethods()
        registerDoubleMethods()

        registerEqualsMethods()

        registerNumberConversions()

        registerStringMethods()
        registerPrintln()
        registerArrayAccess()
        registerTypeMethods()
    }

    fun Runtime.registerBinaryByteMethod(name: String, calc: (a: Byte, b: Byte) -> Int) {
        registerBinaryMethod(Types.Byte, name) { a, b ->
            val result = calc(a.castToByte(), b.castToByte())
            createInt(result)
        }
    }

    fun Runtime.registerBinaryByteMethod2(name: String, calc: (a: Byte, b: Byte) -> Byte) {
        registerBinaryMethod(Types.Byte, name) { a, b ->
            val result = calc(a.castToByte(), b.castToByte())
            createByte(result)
        }
    }

    fun Runtime.registerByteIntMethod(name: String, calc: (a: Byte, b: Int) -> Int) {
        registerBinaryMethod(Types.Byte, Types.Int, name) { a, b ->
            val result = calc(a.castToByte(), b.castToInt())
            createInt(result)
        }
    }

    fun Runtime.registerUByteUIntMethod(name: String, calc: (a: UByte, b: UInt) -> UInt) {
        registerBinaryMethod(Types.UByte, Types.UInt, name) { a, b ->
            val result = calc(a.castToUByte(), b.castToUInt())
            createUInt(result)
        }
    }

    fun Runtime.registerBinaryUByteMethod2(name: String, calc: (a: UByte, b: UByte) -> UByte) {
        registerBinaryMethod(Types.UByte, name) { a, b ->
            val result = calc(a.castToUByte(), b.castToUByte())
            createUByte(result)
        }
    }

    fun Runtime.registerBinaryShortMethod(name: String, calc: (a: Short, b: Short) -> Int) {
        registerBinaryMethod(Types.Short, name) { a, b ->
            val result = calc(a.castToShort(), b.castToShort())
            createInt(result)
        }
    }

    fun Runtime.registerShortIntMethod(name: String, calc: (a: Short, b: Int) -> Int) {
        registerBinaryMethod(Types.Short, Types.Int, name) { a, b ->
            val result = calc(a.castToShort(), b.castToInt())
            createInt(result)
        }
    }

    fun Runtime.registerBinaryShortMethod2(name: String, calc: (a: Short, b: Short) -> Short) {
        registerBinaryMethod(Types.Short, name) { a, b ->
            val result = calc(a.castToShort(), b.castToShort())
            createShort(result)
        }
    }

    fun Runtime.registerUShortUIntMethod(name: String, calc: (a: UShort, b: UInt) -> UInt) {
        registerBinaryMethod(Types.UShort, Types.UInt, name) { a, b ->
            val result = calc(a.castToUShort(), b.castToUInt())
            createUInt(result)
        }
    }

    fun Runtime.registerBinaryUShortMethod2(name: String, calc: (a: UShort, b: UShort) -> UShort) {
        registerBinaryMethod(Types.UShort, name) { a, b ->
            val result = calc(a.castToUShort(), b.castToUShort())
            createUShort(result)
        }
    }

    fun Runtime.registerBinaryIntMethod(name: String, calc: (a: Int, b: Int) -> Int) {
        registerBinaryMethod(Types.Int, name) { a, b ->
            val ai = a.castToInt()
            val bi = b.castToInt()
            val result = calc(ai, bi)
            createInt(result)
        }
    }

    fun Runtime.registerBinaryUIntMethod(name: String, calc: (a: UInt, b: UInt) -> UInt) {
        registerBinaryMethod(Types.UInt, name) { a, b ->
            val result = calc(a.castToUInt(), b.castToUInt())
            createUInt(result)
        }
    }

    fun Runtime.registerBinaryUIntMethod2(name: String, calc: (a: UInt, b: Int) -> UInt) {
        register(Types.UInt, name, listOf(Types.Int)) { a, (b) ->
            val result = calc(a.castToUInt(), b.castToInt())
            createUInt(result)
        }
    }

    fun Runtime.registerBinaryLongMethod(name: String, calc: (a: Long, b: Long) -> Long) {
        registerBinaryMethod(Types.Long, name) { a, b ->
            val result = calc(a.castToLong(), b.castToLong())
            createLong(result)
        }
    }

    fun Runtime.registerBinaryLongMethod2(name: String, calc: (a: Long, b: Int) -> Long) {
        register(Types.Long, name, listOf(Types.Int)) { a, (b) ->
            val result = calc(a.castToLong(), b.castToInt())
            createLong(result)
        }
    }

    fun Runtime.registerBinaryULongMethod(name: String, calc: (a: ULong, b: ULong) -> ULong) {
        registerBinaryMethod(Types.ULong, name) { a, b ->
            val result = calc(a.castToULong(), b.castToULong())
            createULong(result)
        }
    }

    fun Runtime.registerBinaryULongMethod2(name: String, calc: (a: ULong, b: Int) -> ULong) {
        register(Types.ULong, name, listOf(Types.Int)) { a, (b) ->
            val result = calc(a.castToULong(), b.castToInt())
            createULong(result)
        }
    }

    fun Runtime.registerBinaryHalfMethod(name: String, calc: (a: Half, b: Half) -> Half) {
        registerBinaryMethod(Types.Half, name) { a, b ->
            val result = calc(a.castToHalf(), b.castToHalf())
            createHalf(result)
        }
    }

    fun Runtime.registerBinaryFloatMethod(name: String, calc: (a: Float, b: Float) -> Float) {
        registerBinaryMethod(Types.Float, name) { a, b ->
            val result = calc(a.castToFloat(), b.castToFloat())
            createFloat(result)
        }
    }

    fun Runtime.registerBinaryDoubleMethod(name: String, calc: (a: Double, b: Double) -> Double) {
        registerBinaryMethod(Types.Double, name) { a, b ->
            val result = calc(a.castToDouble(), b.castToDouble())
            createDouble(result)
        }
    }

    fun Runtime.registerUnaryMethod(selfType: ClassType, name: String, calc: (self: Instance) -> Instance) {
        register(selfType.clazz, name, emptyList()) { self, _ -> calc(self) }
    }

    fun registerEqualsMethods() {
        val rt = runtime
        for (type in nativeNumbers) {
            // todo we should be able to call equals on larger types, too..., e.g. 0 == 15L
            rt.registerBinaryMethod(type, "equals") { a, b ->
                assertEquals(type, a.clazz.type)
                assertEquals(type, b.clazz.type)
                check(a.rawValue != null)
                check(b.rawValue != null)
                rt.getBool(a.rawValue == b.rawValue)
            }
        }
        rt.register(Types.Byte, "equals", listOf(Types.Int)) { a, (b) ->
            rt.getBool(a.castToByte().toInt() == b.castToInt())
        }
        rt.register(Types.Short, "equals", listOf(Types.Int)) { a, (b) ->
            rt.getBool(a.castToShort().toInt() == b.castToInt())
        }
        rt.register(Types.UByte, "equals", listOf(Types.UInt)) { a, (b) ->
            rt.getBool(a.castToUByte().toUInt() == b.castToUInt())
        }
        rt.register(Types.UShort, "equals", listOf(Types.UInt)) { a, (b) ->
            rt.getBool(a.castToUShort().toUInt() == b.castToUInt())
        }
        rt.register(Types.Char, "equals", listOf(Types.Char)) { a, (b) ->
            rt.getBool(a.castToChar() == b.castToChar())
        }
    }

}