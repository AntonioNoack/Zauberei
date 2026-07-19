package me.anno.zauber.interpreting

import me.anno.utils.Half
import me.anno.utils.StringStyles.DARK_BLUE
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.RED
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.interpreting.ConstExpr.evaluateExpression
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.RuntimeCreate.createString
import me.anno.zauber.interpreting.ZClass.Companion.nativeTypes
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.arithmetic.NullType

class Instance(
    val clazz: ZClass,
    val fields: Array<Instance?>,
    val id: Int
) {

    companion object {
        private val uninitialized = style("undefined", RED)
    }

    var rawValue: Any? = null

    override fun toString(): String {
        val rawValue = rawValueStr()
        val glue = if (rawValue.isEmpty() || fields.isEmpty()) "" else ","
        val fields = fields.indices.joinToString(",", "", glue) { index ->
            val name = style(clazz.fields[index].name, GREEN)
            val value = fields[index]?.toStringInner() ?: uninitialized
            "$name=$value"
        }
        return style("@$id", DARK_BLUE) +
                "[${clazz.type}]($fields$rawValue)"
    }

    fun toStringInner(): String {
        val rv = rawValueStr()
        if (clazz.type is NullType) return style("null", RED) + style("@$id", DARK_BLUE)
        return (clazz.type as ClassType).clazz.toString() +
                style("@$id", DARK_BLUE) +
                (if (rv.isNotEmpty()) "($rv)" else "")
    }

    private fun rawValueStr(): String {
        return when (val rawValue = rawValue) {
            null -> ""
            is Array<*> -> "${rawValue.toList()}"
            is IntArray -> "I${rawValue.toList()}"
            is LongArray -> "J${rawValue.toList()}"
            is FloatArray -> "F${rawValue.toList()}"
            is DoubleArray -> "D${rawValue.toList()}"
            is ByteArray -> "B${rawValue.toList()}"
            is ShortArray -> "S${rawValue.toList()}"
            is CharArray -> "C${rawValue.joinToString()}"
            is BooleanArray -> "Z${rawValue.toList()}"
            is String -> style("\"$rawValue\"", GREEN)
            is Byte, is UByte,
            is Short, is UShort,
            is Int, is UInt,
            is Long, is ULong,
            is Half, is Float, is Double -> style(rawValue.toString(), DARK_BLUE)
            else -> "$rawValue"
        }
    }

    fun checkType(targetType: Type) {
        check(clazz.type == targetType) {
            "Type mismatch: Expected $targetType, but got $this"
        }
    }

    fun castToBool(): Boolean {
        val rt = runtime
        val isTrue = this == rt.getBool(true)
        val isFalse = this == rt.getBool(false)
        check(isTrue || isFalse) { "Expected value to be either true or false, got $this" }
        return isTrue
    }

    fun castToByte(): Byte {
        checkType(Types.Byte)
        return rawValue as? Byte
            ?: error("Found illegal Byte-instance without raw value: $this")
    }

    fun castToUByte(): UByte {
        checkType(Types.UByte)
        return rawValue as? UByte
            ?: error("Found illegal UByte-instance without raw value: $this")
    }

    fun castToShort(): Short {
        checkType(Types.Short)
        return rawValue as? Short
            ?: error("Found illegal Short-instance without raw value: $this")
    }

    fun castToUShort(): UShort {
        checkType(Types.UShort)
        return rawValue as? UShort
            ?: error("Found illegal UShort-instance without raw value: $this")
    }

    fun castToChar(): Char {
        checkType(Types.Char)
        return rawValue as? Char
            ?: error("Found illegal Char-instance without raw value: $this")
    }

    fun castToInt(): Int {
        checkType(Types.Int)
        return rawValue as? Int
            ?: error("Found illegal Int-instance without raw value: $this")
    }

    fun castToUInt(): UInt {
        checkType(Types.UInt)
        return rawValue as? UInt
            ?: error("Found illegal UInt-instance without raw value: $this")
    }

    fun castToLong(): Long {
        checkType(Types.Long)
        return rawValue as? Long
            ?: error("Found illegal Long-instance without raw value: $this")
    }

    fun castToULong(): ULong {
        checkType(Types.ULong)
        return rawValue as? ULong
            ?: error("Found illegal ULong-instance without raw value: $this")
    }

    fun castToHalf(): Half {
        checkType(Types.Half)
        return rawValue as? Half
            ?: error("Found illegal Half-instance without raw value: $this")
    }

    fun castToFloat(): Float {
        checkType(Types.Float)
        return rawValue as? Float
            ?: error("Found illegal Float-instance without raw value: $this")
    }

    fun castToDouble(): Double {
        checkType(Types.Double)
        return rawValue as? Double
            ?: error("Found illegal Double-instance without raw value: $this")
    }

    fun castToString(): String {
        checkType(Types.String)
        if (rawValue == null) {
            // a byte array
            val content = fields[0]!!
            val string = when (val bytes = content.rawValue) {
                is ByteArray -> bytes
                is Array<*> -> ByteArray(bytes.size) { (bytes[it] as Instance).castToByte() }
                else -> throw NotImplementedError()
            }.decodeToString()
            rawValue = string
            return string
        }
        return rawValue as String
    }

    fun castToType(): Type {
        val ct = clazz.type
        check(
            ct == Types.ClassType || ct == Types.TypeT ||
                    ct == Types.UnionType || ct == Types.GenericType
        )
        return rawValue as Type
    }

    fun cloneIfValue(): Instance {
        return if (clazz.isValueClass) clone() else this
    }

    fun clone(): Instance {
        check(clazz.type is ClassType)
        val newId = runtime.nextInstanceId()
        val newProperties = Array(fields.size) {
            fields[it]?.cloneIfValue()
        }
        return Instance(clazz, newProperties, newId)
    }

    fun set(fieldName: String, value: String) {
        val fieldIndex = clazz.fields.indexOfFirst { it.name == fieldName }
        if (fieldIndex < 0) return
        fields[fieldIndex] = runtime.createString(value)
    }

    operator fun get(fieldName: String): Instance {
        val field = clazz.fields.firstOrNull { it.name == fieldName }
            ?: error("Missing field '$fieldName' in $clazz")
        return get(field)
    }

    operator fun get(field: Field): Instance {
        val fieldIndex = clazz.fields.indexOf(field)

        if (fieldIndex < 0) {

            if (field.scope == (clazz.type as? ClassType)?.clazz &&
                field.name == "content" &&
                clazz.type in nativeTypes
            ) return this

            if (field.scope.pathStr == "java.lang.Class" &&
                field.name == "classLoader"
            ) {
                val field1 = Types.ClassType.clazz
                    .fields.firstOrNull { it.name == field.name }
                    ?: error("Missing $field in $clazz")
                return get(field1)
            }

            error("Instance $this does not have field $field (${field.scope})")
        }

        if (fieldIndex >= fields.size) {
            error("Outdated instance? $this")
        }

        if (fields[fieldIndex] == null &&
            clazz.type == Types.String &&
            field.name == "content"
        ) createStringContentArray(fieldIndex)

        if (fields[fieldIndex] == null &&
            field.flags.hasFlag(Flags.CONSTEXPR)
        ) initializeConstant(field, fieldIndex)

        return fields[fieldIndex]
            ?: error("$this.${field.toStringWithoutDefault()}[$fieldIndex] accessed before initialization")
    }

    private fun initializeConstant(field: Field, fieldIndex: Int) {
        val value = field.initialValue!!
        fields[fieldIndex] = evaluateExpression(value, field.flags, field.valueType)
    }

    private fun createStringContentArray(fieldIndex: Int) {
        TODO("Create string content array for $this")
    }

    operator fun set(fieldName: String, value: Instance) {
        val fieldIndex = clazz.fields.indexOfFirst { it.name == fieldName }
        if (fieldIndex < 0) return
        fields[fieldIndex] = value
    }

    fun hasProperty(fieldName: String): Boolean {
        return clazz.fields.any { it.name == fieldName }
    }
}