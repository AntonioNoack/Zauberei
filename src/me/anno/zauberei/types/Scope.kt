package me.anno.zauberei.types

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.*
import me.anno.zauberei.astbuilder.Function
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.tokenizer.TokenList

/**
 * Scope / Package / Class / Object / Interface ...
 * keywords tell you what it is
 * */
class Scope(val name: String? = null, val parent: Scope? = null) : Type() {

    var fileName: String? = parent?.fileName

    val keywords = ArrayList<String>()
    val children = ArrayList<Scope>()
    val sources = ArrayList<TokenList>()

    val constructors = ArrayList<Constructor>()
    val initialization = ArrayList<Expression>()
    val functions = ArrayList<Function>()
    val fields = ArrayList<Field>()

    var primaryConstructorParams: List<Parameter>? = null
    val superCalls = ArrayList<SuperCall>()

    val enumValues = ArrayList<Expression>()
    val typeAliases = ArrayList<TypeAlias>()

    var typeParams: List<Parameter> = emptyList()

    fun getOrPut(name: String): Scope {

        if (name == "InnerZipFile" && parent == null)
            throw IllegalStateException("Asking for $name on a global level???")

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

    fun resolveTypeInner(name: String): Scope? {
        if (name == this.name) return this
        for (child in children) {
            if (child.name == name) return child
        }
        if (fileName != parent?.fileName) return null
        return parent?.resolveTypeInner(name)
    }

    fun resolveTypeSameFolder(name: String): Scope? {
        var folderScope = this
        while (folderScope.fileName == fileName) {
            folderScope = folderScope.parent ?: return null
        }
        // println("rtsf[$name,$this] -> ${folderScope.children.map { it.name }}")
        for (child in folderScope.children) {
            if (child.name == name) return child
        }
        return null
    }

    fun resolveGenericType(name: String): Type? {
        for (param in typeParams) {
            if (param.name == name) {
                return GenericType(this, name)
            }
        }
        for(superCall in superCalls) {
            val bySuper = superCall.type
        }
        // todo check this and any parent class for type parameters
        return null
    }

    fun resolveTypeOrNull(name: String, astBuilder: ASTBuilder): Type? {
        val insideThisFile = resolveTypeInner(name)
        if (insideThisFile != null) return insideThisFile

        val genericType = resolveGenericType(name)
        if (genericType != null) return genericType

        val imports = astBuilder.imports
        for (import in imports) {
            val path = import.path
            if (import.allChildren) {
                // scan all of that scope
                for (child in path.children) {
                    if (child.name == name) return child
                }
            } else if (path.name == name) {
                return path
            }
        }

        val sameFolder = resolveTypeSameFolder(name)
        if (sameFolder != null) return sameFolder

        val standardType = StandardTypes.standardTypes[name]
        if (standardType != null) return standardType

        // we must also check root for any valid paths...
        for (child in root.children) {
            if (child.name == name) {
                return child
            }
        }

        return null
    }

    fun resolveType(name: String, astBuilder: ASTBuilder): Type {
        return resolveTypeOrNull(name, astBuilder)
            ?: throw IllegalStateException("Unresolved type '$name' in $this")
    }

    private var nextAnonymousName = 0

    fun generateName(prefix: String): String {
        return "$$prefix${nextAnonymousName++}"
    }

    override fun toString(): String {
        return "Package[${path.joinToString(".")}]"
    }

}