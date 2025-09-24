package me.anno.zauberei.types

object NameResolution {
    fun resolveNames(root: Scope) {
        resolveNamesImpl(root)
        for (child in root.children) {
            resolveNames(child)
        }
    }

    private fun resolveNamesImpl(root: Scope) {
        // todo for any unresolved type,
        //  check whether the name is
        //  1) a local class,
        //  2) an import,
        //  3) a default import,

    }
}