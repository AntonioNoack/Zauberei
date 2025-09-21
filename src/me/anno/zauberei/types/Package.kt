package me.anno.zauberei.types

class Package(val name: String? = null, val parent: Package? = null) {
    val children = ArrayList<Package>()

    fun getOrPut(name: String): Package {
        var child = children.firstOrNull { it.name == name }
        if (child != null) return child

        child = Package(name, this)
        children.add(child)
        return child
    }
}