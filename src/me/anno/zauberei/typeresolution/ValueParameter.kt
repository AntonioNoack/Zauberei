package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type

abstract class ValueParameter(val name: String?) {
    abstract fun getType(targetType: Type): Type
}