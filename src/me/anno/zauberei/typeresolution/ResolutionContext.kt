package me.anno.zauberei.typeresolution

import me.anno.zauberei.typeresolution.TypeResolution.typeToScope
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ResolutionContext(
    val codeScope: Scope, // 3rd
    val selfType: Type?, // 2nd
    /**
     * Whether something without type, like while(true){}, is supported
     * */
    val allowTypeless: Boolean,
    val targetType: Type?
) {

    val selfScope = typeToScope(selfType)

    fun withTargetType(newTargetType: Type?): ResolutionContext {
        if (newTargetType == targetType) return this
        return ResolutionContext(
            codeScope, selfType,
            allowTypeless, newTargetType
        )
    }

    fun withSelfType(newSelfType: Type?): ResolutionContext {
        if (newSelfType == selfType) return this
        return ResolutionContext(
            codeScope, newSelfType,
            allowTypeless, targetType
        )
    }
}