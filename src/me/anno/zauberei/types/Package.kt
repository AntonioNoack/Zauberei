package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.Function
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.expression.Expression

class Package(val name: String? = null, val parent: Package? = null) {

    val keywords = ArrayList<String>()

    val children = ArrayList<Package>()

    val functions = ArrayList<Function>()
    val fields = ArrayList<Field>()

    val initialization = ArrayList<Expression>()

    var primaryConstructorParams: List<Parameter>? = null
    val superCalls = ArrayList<Expression>()
    val enumValues = ArrayList<Expression>()

    fun getOrPut(name: String): Package {
        var child = children.firstOrNull { it.name == name }
        if (child != null) return child

        child = Package(name, this)
        children.add(child)
        return child
    }

    val path: List<String>
        get() {
            val path = ArrayList<String>()
            var that = this
            while (true) {
                if (that.name != null) path.add(that.name)
                that = that.parent ?: break
            }
            path.reverse()
            return path
        }

    override fun toString(): String {
        return "Package[${path.joinToString(".")}]"
    }

}