package me.anno.zauberei.types

import me.anno.zauberei.Compile.root

object Types {
    val AnyType = ClassType(root.getOrPut("Any"), emptyList())
    val NullableAnyType = NullableType(AnyType)
}