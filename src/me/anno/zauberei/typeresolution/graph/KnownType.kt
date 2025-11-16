package me.anno.zauberei.typeresolution.graph

import me.anno.zauberei.types.Type

data class KnownType(val type: Type) : ResolvingType()