package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Scope

object TypeResolution {

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::collectConstrains)
    }

    // todo resolve all unclear types:
    //  all variables, all expressions, all method calls (because their return type is necessary to find the types)

    fun collectConstrains(scope: Scope) {
        for (field in scope.fields) {
            // todo field itself
            // todo getter
            // todo setter
        }
        for (init in scope.initialization) {

        }
        for (function in scope.functions) {

        }
    }

    fun forEachScope(scope: Scope, callback: (Scope) -> Unit) {
        callback(scope)
        for (child in scope.children) {
            forEachScope(child, callback)
        }
    }
}