package me.anno.zauberei.types

import me.anno.zauberei.Compile.root

object Types {

    fun getScope(i: String): Scope {
        if ('.' !in i) return root.getOrPut(i)
        val parts = i.split('.')
        var scope = root
        for (part in parts) {
            scope = scope.getOrPut(part)
        }
        return scope
    }

    fun getType(i: String) = ClassType(getScope(i), emptyList())

    val AnyType = getType("Any")
    val NullableAnyType = NullableType(AnyType)
    val UnitType = getType("Unit")
    val CharType = getType("Char")
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
}