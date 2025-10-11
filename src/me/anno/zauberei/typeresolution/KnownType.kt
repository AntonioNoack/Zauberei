package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type

data class KnownType(val type: Type) : ResolvingType()