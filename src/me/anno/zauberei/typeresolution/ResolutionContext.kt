package me.anno.zauberei.typeresolution

import me.anno.zauberei.typeresolution.TypeResolution.typeToScope
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

class ResolutionContext(
    val codeScope: Scope, // 3rd
    val selfType: Type?, // 2nd
    val selfScope: Scope?,
    /**
     * Whether something without type, like while(true){}, is supported
     * */
    val allowTypeless: Boolean,
    val targetLambdaType: Type?
) {
    fun withTargetLambdaType(newTargetLambdaType: Type?): ResolutionContext {
        if (newTargetLambdaType == targetLambdaType) return this
        return ResolutionContext(
            codeScope, selfType, selfScope,
            allowTypeless, newTargetLambdaType
        )
    }

    fun withSelfType(newSelfType: Type?): ResolutionContext {
        if (newSelfType == selfType) return this
        return ResolutionContext(
            codeScope, newSelfType, typeToScope(newSelfType),
            allowTypeless, targetLambdaType
        )
    }
}