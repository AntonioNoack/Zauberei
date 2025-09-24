package me.anno.zauberei.types

// todo resolve all unclear types:
//  all variables, all expressions, all method calls (because their return type is necessary to find the types)
object TypeResolution {

    fun resolveTypes(clazz: Scope) {
        resolveTypesImpl(clazz)
        for (child in clazz.children) {
            resolveTypes(child)
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