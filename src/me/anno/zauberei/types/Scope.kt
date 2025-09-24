package me.anno.zauberei.types

import me.anno.zauberei.astbuilder.Constructor
import me.anno.zauberei.astbuilder.Function
import me.anno.zauberei.astbuilder.Parameter
import me.anno.zauberei.astbuilder.expression.Expression

/**
 * Scope / Package / Class / Object / Interface ...
 * keywords tell you what it is
 * */
class Scope(val name: String? = null, val parent: Scope? = null) {

    val keywords = ArrayList<String>()
    val children = ArrayList<Scope>()

    val constructors = ArrayList<Constructor>()
    val initialization = ArrayList<Expression>()
    val functions = ArrayList<Function>()
    val fields = ArrayList<Field>()

    var primaryConstructorParams: List<Parameter>? = null
    val superCalls = ArrayList<Expression>()
    val enumValues = ArrayList<Expression>()
    val typeAliases = ArrayList<TypeAlias>()

    var typeParams: List<Parameter> = emptyList()

    fun getOrPut(name: String): Scope {
        var child = children.firstOrNull { it.name == name }
        if (child != null) return child

        child = Scope(name, this)
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

    private var nextAnonymousName = 0

    fun generateName(prefix: String): String {
        return "$$prefix${nextAnonymousName++}"
    }

    override fun toString(): String {
        return "Package[${path.joinToString(".")}]"
    }

}