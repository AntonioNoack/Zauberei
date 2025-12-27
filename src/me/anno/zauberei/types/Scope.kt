package me.anno.zauberei.types

import me.anno.zauberei.Compile.root
import me.anno.zauberei.astbuilder.*
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.types.BooleanUtils.and
import me.anno.zauberei.types.BooleanUtils.not
import me.anno.zauberei.types.impl.ClassType
import me.anno.zauberei.types.impl.GenericType

/**
 * Scope / Package / Class / Object / Interface ...
 * keywords tell you what it is
 * */
class Scope(val name: String, val parent: Scope? = null) {

    var scopeType: ScopeType? = null

    var fileName: String? = parent?.fileName

    val keywords = ArrayList<String>()
    val children = ArrayList<Scope>()
    val sources = ArrayList<TokenList>()

    val constructors = ArrayList<Constructor>()
    val code = ArrayList<Expression>()

    val methods = ArrayList<Method>()
    val fields = ArrayList<Field>()

    val superCalls = ArrayList<SuperCall>()
    val superCallNames = ArrayList<SuperCallName>()

    val enumValues = ArrayList<EnumEntry>()
    var typeAlias: Type? = null

    var selfAsMethod: Method? = null

    var typeParameters: List<Parameter> = emptyList()
    var hasTypeParameters = false

    val typeWithoutArgs = ClassType(this, null)

    /**
     * used for type resolution
     * */
    var imports: List<Import2> = emptyList()

    /**
     * each object Scope is also one field, and we store that here
     * */
    var objectField: Field? = null

    /**
     * for each if/else-chain, these shall be filled in
     * */
    var branchCondition: Expression? = null
    var branchConditionTrue: Boolean = false

    fun addCondition(condition: Expression, isTrue: Boolean) {
        var branchCondition = branchCondition
        if (branchCondition == null) {
            this.branchCondition = condition
            branchConditionTrue = isTrue
        } else {
            if (!branchConditionTrue) {
                branchCondition = branchCondition.not()
            }
            var condition = condition
            if (!isTrue) {
                condition = condition.not()
            }
            this.branchCondition = branchCondition.and(condition)
            branchConditionTrue = true
        }
    }

    var primaryConstructorScope: Scope? = null
    fun getOrCreatePrimConstructorScope(): Scope {
        return primaryConstructorScope ?: run {
            val scope = getOrPut("prim", ScopeType.PRIMARY_CONSTRUCTOR)
            primaryConstructorScope = scope
            scope
        }
    }

    fun addField(field: Field) {
        if (fields.any { it.name == field.name }) {
            val other = fields.first { it.name == field.name }
            throw IllegalStateException(
                "Each field must only be declared once per scope [$pathStr], " +
                        "${field.name} at ${TokenListIndex.resolveOrigin(field.origin)} vs ${
                            TokenListIndex.resolveOrigin(
                                other.origin
                            )
                        }"
            )
        }
        fields.add(field)
    }

    fun getOrPut(name: String, scopeType: ScopeType?): Scope {

        // hack, because Kotlin forbids us from defining functions inside Kotlin scope
        val name = if (parent == null && name == "kotlin") "zauber" else name

        if (this.name == "Companion" && name == "ECSMeshShader")
            throw IllegalStateException("ECSMeshShader is not a part of a Companion")

        if (name == "InnerZipFile" && parent == null)
            throw IllegalStateException("Asking for $name on a global level???")

        var child = children.firstOrNull { it.name == name }
        if (child != null) {
            if (child.fileName == null) child.fileName = fileName
            child.mergeScopeTypes(scopeType)
            return child
        }

        child = Scope(name, this)
        child.scopeType = scopeType
        children.add(child)
        return child
    }

    fun mergeScopeTypes(scopeType: ScopeType?) {
        val self = this
        if (scopeType != null) {
            if (self.scopeType == null || self.scopeType == scopeType) self.scopeType = scopeType
            else throw IllegalStateException("ScopeType conflict! ${self.scopeType} vs $scopeType")
        }
    }

    fun getOrPut(name: String, fileName: String, scopeType: ScopeType?): Scope {
        val child = getOrPut(name, scopeType)
        if (child.fileName == null) child.fileName = fileName
        return child
    }

    val path: List<String>
        get() {
            val path = ArrayList<String>()
            var that = this
            while (true) {
                if (that.name == "*") break
                path.add(that.name)
                that = that.parent!!
            }
            path.reverse()
            return path
        }

    val pathStr: String
        get() = path.joinToString(".")

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
        for (param in typeParameters) {
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
        ) return parent.typeWithoutArgs

        if (searchInside) {
            val insideThisFile = resolveTypeInner(name)
            if (insideThisFile != null) return insideThisFile.typeWithoutArgs
        }

        val genericType = resolveGenericType(name)
        if (genericType != null) return genericType

        for (import in imports) {
            val path = import.path
            if (import.allChildren) {
                // scan all of that scope
                for (child in path.children) {
                    if (child.name == name) {
                        return child.typeWithoutArgs
                    }
                }
            } else if (import.name == name) {
                return path.typeWithoutArgs
            }
        }

        val sameFolder = resolveTypeSameFolder(name)
        if (sameFolder != null) return sameFolder.typeWithoutArgs

        // helper at startup / for tests
        val standardType = StandardTypes.standardClasses[name]
        if (standardType != null) return standardType.typeWithoutArgs

        // check siblings
        if (parent != null) {
            for (child in parent.children) {
                if (child.name == name) return child.typeWithoutArgs
            }
        }

        // we must also check root for any valid paths...
        for (child in root.children) {
            if (child.name == name) {
                return child.typeWithoutArgs
            }
        }

        return null
    }

    fun resolveType(
        name: String, typeParameters: List<Parameter>,
        functionScope: Scope, astBuilder: ASTBuilder,
    ): Type {
        val typeParam = typeParameters.firstOrNull { it.name == name }
        if (typeParam != null) return GenericType(functionScope, typeParam.name)
        return resolveType(name, astBuilder)
    }

    fun resolveType(name: String, astBuilder: ASTBuilder): Type {
        val name = if (name == "kotlin") "zauber" else name
        return resolveTypeOrNull(name, astBuilder)
            ?: throw IllegalStateException("Unresolved type '$name' in $this, children: ${children.map { it.name }}")
    }

    private var nextAnonymousName = 0
    fun generateName(prefix: String): String {
        return "$$prefix${nextAnonymousName++}"
    }

    val parentIfSameFile: Scope?
        get() {
            val scopeType = scopeType
            return if (
                scopeType != ScopeType.PACKAGE &&
                scopeType != null
            ) {
                parent
            } else null
        }

    override fun toString(): String = pathStr

    override fun equals(other: Any?): Boolean {
        return other is Scope && path == other.path
    }

    override fun hashCode(): Int {
        var hash = 1
        for (i in path.indices) {
            hash = hash * 31 + path[i].hashCode()
        }
        return hash
    }

}