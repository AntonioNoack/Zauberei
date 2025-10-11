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
    var privatePrimaryConstructor = false

    val superCalls = ArrayList<SuperCall>()

    val superCallNames = ArrayList<SuperCallName>()

    val enumValues = ArrayList<Expression>()
    var typeAlias: Type? = null

    var typeParams: List<Parameter> = emptyList()

    fun getOrPut(name: String): Scope {

        if (this.name == "Companion" && name == "ECSMeshShader")
            throw IllegalStateException("ECSMeshShader is not a part of a Companion")

        if (name == "InnerZipFile" && parent == null)
            throw IllegalStateException("Asking for $name on a global level???")

        var child = children.firstOrNull { it.name == name }
        if (child != null) {
            if (child.fileName == null) child.fileName = fileName
            return child
        }

        child = Scope(name, this)
        children.add(child)
        return child
    }

    fun getOrPut(name: String, fileName: String): Scope {

        var child = children.firstOrNull { it.name == name }
        if (child != null) {
            if (child.fileName == null) child.fileName = fileName
            return child
        }

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

        val parent = parent
        if (parent != null && fileName == parent.fileName) {
            val byParent = parent.resolveTypeInner(name)
            if (byParent != null) return byParent
        }

        forEachSuperType { type ->
            val scope = extractScope(type)
            val bySuperCall = scope.resolveTypeInner(name)
            // println("rti[$name,$this] -> $type -> $scope -> $bySuperCall")
            if (bySuperCall != null) return bySuperCall
        }

        return null
    }

    fun isMutableVariable(name: String): Boolean? {
        TODO()
    }

    private inline fun forEachSuperType(callback: (Type) -> Unit) {
        if (superCalls.size < superCallNames.size) {
            for (superCall in superCallNames) {
                val resolved = superCall.resolved
                if (resolved != null) {
                    callback(resolved)
                } else {
                    val type = resolveTypeOrNull(superCall.name, superCall.imports, false)
                    if (type != null) {
                        superCall.resolved = type
                        callback(type)
                    } else throw IllegalStateException("Could not resolve ${superCall.name} inside $this!")
                }
            }
        } else {
            for (superCall in superCalls) {
                callback(superCall.type)
            }
        }
    }

    private fun extractScope(type: Type): Scope {
        return when (type) {
            is ClassType -> type.clazz
            is Scope -> type
            else -> throw NotImplementedError("$type")
        }
    }

    fun resolveTypeSameFolder(name: String): Scope? {
        var folderScope = this
        if (fileName == null) {
            // throw IllegalStateException("No file assigned to $this?")
            return null
        }
        while (folderScope.fileName == fileName) {
            folderScope = folderScope.parent ?: return null
        }
        // println("rtsf[$name,$this] -> $folderScope -> ${folderScope.children.map { it.name }}")
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
        for (superCall in superCalls) {
            val bySuper = superCall.type
        }
        // todo check this and any parent class for type parameters
        return null
    }

    fun resolveTypeOrNull(name: String, astBuilder: ASTBuilder): Type? =
        resolveTypeOrNull(name, astBuilder.imports, true)

    fun resolveTypeOrNull(
        name: String, imports: List<Import>,
        searchInside: Boolean
    ): Type? {

        // println("Resolving $name in $this ($searchInside, $fileName, ${parent?.fileName})")

        if (parent != null && parent.fileName == fileName &&
            parent.name == name
        ) return parent

        if (searchInside) {
            val insideThisFile = resolveTypeInner(name)
            if (insideThisFile != null) return insideThisFile
        }

        val genericType = resolveGenericType(name)
        if (genericType != null) return genericType

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

        // check siblings
        if (parent != null) {
            for (child in parent.children) {
                if (child.name == name) return child
            }
        }

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
            ?: throw IllegalStateException("Unresolved type '$name' in $this, children: ${children.map { it.name }}")
    }

    private var nextAnonymousName = 0

    fun generateName(prefix: String): String {
        return "$$prefix${nextAnonymousName++}"
    }

    override fun toString(): String {
        return "Package[${path.joinToString(".")}]"
    }

}