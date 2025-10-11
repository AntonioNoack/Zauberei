package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Scope

// todo resolve all unclear types:
//  all variables, all expressions, all method calls (because their return type is necessary to find the types)
object TypeResolution {

    fun resolveTypesAndNames(clazz: Scope) {
        resolveTypesImpl(clazz)
        for (child in clazz.children) {
            resolveTypesAndNames(child)
        }
    }

    private fun resolveTypesImpl(clazz: Scope) {
        for (field in clazz.fields) {
            // todo field itself
            // todo getter
            // todo setter
        }
        for (init in clazz.initialization) {

        }
        for (function in clazz.functions) {

        }
    }
}