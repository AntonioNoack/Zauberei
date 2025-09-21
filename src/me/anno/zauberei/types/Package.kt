package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.Function

class Package(val name: String? = null, val parent: Package? = null) {

    val keywords = ArrayList<String>()

    val children = ArrayList<Package>()
    val functions = ArrayList<Function>()

    fun getOrPut(name: String): Package {
        var child = children.firstOrNull { it.name == name }
        if (child != null) return child

        child = Package(name, this)
        children.add(child)
        return child
    }
}