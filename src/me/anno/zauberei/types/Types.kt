package me.anno.zauberei.types

import me.anno.zauberei.Compile.root
import me.anno.zauberei.typeresolution.LinearTypeResolution.langScope

object Types {

    fun getScope(i: String): Scope {
        if ('.' !in i) return langScope.getOrPut(i, null)
        val parts = i.split('.')
        var scope = root
        for (part in parts) {
            scope = scope.getOrPut(part, null)
        }
        return scope
    }

    fun getType(i: String): ClassType = ClassType(getScope(i), emptyList())

    val AnyType = getType("Any")
    val NullableAnyType = NullableType(AnyType)
    val UnitType = getType("Unit")
    val CharType = getType("Char")
    val ByteType = getType("Byte")
    val ShortType = getType("Short")
    val IntType = getType("Int")
    val LongType = getType("Long")
    val FloatType = getType("Float")
    val DoubleType = getType("Double")
    val UIntType = getType("UInt")
    val ULongType = getType("ULong")
    val HalfType = getType("Half")
    val StringType = getType("String")
    val TypelessType = getType("Typeless")
    val ThrowableType = getType("Throwable")
    val NothingType = getType("Nothing")
    val BooleanType = getType("Boolean")
    val ArrayType = getType("Array")

    // todo yes, it is Iterable<*>, but * = Nothing still feels wrong :/
    val AnyIterableType = ClassType(getScope("Iterable"), listOf(NothingType))
    val AnyClassType = ClassType(getScope("Class"), listOf(NothingType))
}