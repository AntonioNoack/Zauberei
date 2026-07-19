package me.anno.zauber.scope

import me.anno.libraries.Library
import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.Zauber.STDLIB_NAME
import me.anno.zauber.ast.FlagSet
import me.anno.zauber.ast.rich.Annotation
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasAnyFlag
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.TokenListIndex
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.unresolved.LambdaExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.ast.rich.parser.ASTBuilderBase
import me.anno.zauber.expansion.AddSuperCallToPackages
import me.anno.zauber.expansion.ImplicitConversions
import me.anno.zauber.expansion.DefaultParameters
import me.anno.zauber.expansion.EarlyTypeResolution
import me.anno.zauber.expansion.MethodOverrides
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.Import
import me.anno.zauber.types.StandardTypes
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.SelfType
import me.anno.zauber.types.impl.ThisType
import me.anno.zauber.types.impl.unresolved.UnresolvedClassType
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scope / Package / Class / Object / Interface ...
 * keywords tell you what it is
 * */
class Scope(val name: String, val parent: Scope? = null) {

    var scopeType: ScopeType? = null
    var sourceLibrary: Library? = null

    val path: List<String> = if (parent != null) parent.path + name else emptyList()
    val pathStr: String = path.joinToString(".")
    val depth get() = path.size

    var fileName: String? = parent?.fileName

    var flags: FlagSet = 0
    val annotations = ArrayList<Annotation>()
    val children = ArrayList<Scope>()

    val code: ArrayList<Expression>
        get() = (selfAsConstructor!!.body as ExpressionList).list as ArrayList<Expression>

    // todo we use this in many places, so we should make it explicit
    val constructors0: List<Constructor>
        get() = children.mapNotNull { it.selfAsConstructor }

    fun getMethods(scopeInitType: ScopeInitType): List<Method> {
        this[scopeInitType]

        val methods = methods
        for (i in methods.indices) {
            val method = methods[i]
            method.scope[scopeInitType]
        }
        return methods
    }

    val methods = ArrayList<Method>()

    var companionObject: Scope? = null
        private set

    /**
     * targetType -> conversionMethod
     * */
    val conversionMethods = HashMap<ClassType, Method>()

    val fields = ArrayList<Field>()

    val superCalls = ArrayList<SuperCall>()
    val sealedPermits = ArrayList<Type>(0) // for Java
    val capturedFields = HashSet<Field>()

    val enumEntries: List<Scope>
        get() = children
            .filter { it.scopeType == ScopeType.ENUM_ENTRY_CLASS }
            .map { it[ScopeInitType.AFTER_DISCOVERY] }

    private val initParts = ArrayList<ScopeInit>(4)
    var scopeInitType = ScopeInitType.entries.first()
        private set

    fun addInitPart(scopeInitType: ScopeInitType, runnable: (Scope) -> Unit) {
        addInitPart(ScopeInit(scopeInitType, runnable))
    }

    fun addInitPart(scopeInit: ScopeInit) {
        // println("Adding ${scopeInit.type} to '$pathStr'")
        check(scopeInit.type >= scopeInitType) { "Cannot add ${scopeInit.type} to '$pathStr', when $scopeInitType was already queried" }
        initParts.add(scopeInit)
        initParts.sort()
    }

    init {
        addInitPart(AddSuperCallToPackages.addSuperCallToPackages)
        addInitPart(EarlyTypeResolution.typeResolutionCreator)
        addInitPart(ImplicitConversions.conversionMethodRegistrator)
        addInitPart(DefaultParameters.defaultParameterCreator)
        addInitPart(MethodOverrides.methodOverrideCreator)
    }

    operator fun get(scopeInitType: ScopeInitType): Scope {
        // println("Querying $scopeInitType in '$pathStr', stored: ${initParts.map { it.type }}")

        while (initParts.isNotEmpty() && initParts.last().type < scopeInitType) {
            val element = initParts.removeLast()
            parent?.get(element.type.next())
            this.scopeInitType = element.type
            // println("Running ${element.type} in '$pathStr'...")
            element.runnable(this)
            // println("... Finished ${element.type} in '$pathStr'")
        }
        return this
    }

    // only one can be true, so we can store just one field, and extract everything else
    private var selfAs: Any? = null
        set(value) {
            if (field !== value) {
                if (field is Method) parent!!.methods.remove(field)
                if (value is Method) parent!!.methods.add(value)
            }
            field = value
        }

    var selfAsTypeAlias: Type?
        get() = selfAs as? Type
        set(value) {
            selfAs = value
        }

    var selfAsConstructor: Constructor?
        get() = selfAs as? Constructor
        set(value) {
            selfAs = value
        }

    var selfAsMethod: Method?
        get() = selfAs as? Method
        set(value) {
            selfAs = value
        }

    var selfAsLambda: LambdaExpression?
        get() = selfAs as? LambdaExpression
        set(value) {
            selfAs = value
        }

    var selfAsField: Field?
        get() = selfAs as? Field
        set(value) {
            selfAs = value
        }

    /**
     * this is "" or labeled, wherever we can break/continue to.
     * this is null, if you cannot jump there (e.g. if/else)
     * */
    var jumpLabel: String? = null

    var declaredTypeParameters: List<Parameter> = emptyList()
        private set

    /**
     * Contains this scope's type parameters plus all outer/parent scope type parameters.
     * Outer parameters come first, inner parameters come last.
     * */
    var typeParameters: List<Parameter> = emptyList()
        private set

    private fun findOuterClassTypeParams(): List<Parameter> {
        if (!isInnerClass()) return emptyList()
        val parent = this@Scope.parent ?: error("Inner class $this must have parent")
        check(parent.isClass()) { "Inner class $this must be inside a class, got ${parent.scopeType}" }
        check(parent.hasTypeParameters) { "$parent misses type parameters" }
        return parent.typeParameters
    }

    fun setEmptyTypeParams() {
        setTypeParams(emptyList())
    }

    fun setTypeParams(params: List<Parameter>) {

        if (params.isEmpty() && "me.anno.zauber.typeresolution.members.ResolvedMember" == pathStr) {
            IllegalStateException("Testing-Bug: ResolvedMember has a parameter").printStackTrace()
            error("Testing-Bug: ResolvedMember has a parameter")
        }

        if (hasTypeParameters) {
            assertEquals(params.size, declaredTypeParameters.size) {
                "Type-Param count mismatch: $params vs $declaredTypeParameters"
            }
        }

        declaredTypeParameters = params
        typeParameters = findOuterClassTypeParams() + params
        hasTypeParameters = true
    }

    var hasTypeParameters = false
        private set

    @Deprecated("Only for testing")
    fun resetTypeParams() {
        typeParameters = emptyList()
        declaredTypeParameters = emptyList()
        hasTypeParameters = false
    }

    @Deprecated("There is few cases, where we don't need or don't have generic parameters")
    val typeWithoutArgs = ClassType(this, null)

    val typeWithArgs = UnresolvedClassType(this)
    val typeWithArgs2 get() = typeWithArgs.resolvedName

    /**
     * each object Scope is also one field, and we store that here
     * */
    var objectField: Field? = null

    fun getOrCreateObjectField(origin: Long): Field {
        check(isObjectLike()) { "Expected $this to be object-like, got $scopeType" }
        if (objectField == null) objectField = addField(
            null, false, isMutable = false, null, OBJECT_FIELD_NAME,
            ClassType(this, emptyList(), origin, true),
            /* todo should we set initialValue? */ null, Flags.NONE, origin
        )
        return objectField!!
    }

    fun getOrPutCompanion(): Scope {
        val old = companionObject
        if (old != null) return old

        val scope = getOrPut("Companion", ScopeType.COMPANION_OBJECT)
        scope.setEmptyTypeParams()
        return scope
    }

    /**
     * for each if/else-chain, these shall be filled in
     * */
    var branchConditions: List<Expression> = emptyList()
    fun addCondition(condition: Expression) {
        if (condition in branchConditions) return
        branchConditions += condition
    }

    var primaryConstructorScope: Scope? = null
        private set

    fun getOrCreatePrimaryConstructorScope(): Scope {
        return primaryConstructorScope ?: run {
            val scope = getOrPut("prim", ScopeType.CONSTRUCTOR)
            scope.setEmptyTypeParams()

            primaryConstructorScope = scope
            val superCall = if (pathStr == "zauber.Any") null
            else InnerSuperCall(InnerSuperCallTarget.SUPER, emptyList(), -1)
            scope.selfAsConstructor = Constructor(
                emptyList(), scope,
                superCall, ExpressionList(ArrayList(), scope, -1),
                Flags.SYNTHETIC, -1
            )
            scope
        }
    }

    fun getOrCreatePrimaryConstructor(): Constructor {
        return getOrCreatePrimaryConstructorScope()
            .selfAsConstructor!!
    }

    fun addField(
        selfType: Type?, // may be null inside methods (self is stack) and on package level (self is static)
        explicitSelfType: Boolean,

        isMutable: Boolean,
        byParameter: Any?, // Parameter | LambdaParameter | null

        name: String,
        valueType: Type?,
        initialValue: Expression?,
        flags: FlagSet,
        origin: Long
    ): Field {
        check((selfType != null) == explicitSelfType)

        val sameField = fields.firstOrNull { it.name == name }
        if (sameField != null) {
            return sameField
        }

        val instance = Field(
            this, selfType, explicitSelfType, isMutable, byParameter,
            name, valueType, initialValue, flags, origin
        )

        check(instance !in fields)
        fields.add(instance)
        instance.fieldScope
        return instance
    }

    fun addField(field: Field): Field {
        val other = fields.firstOrNull { oldField -> oldField.name == field.name }
        if (other != null) {
            if (other === field) return field
            error(
                "Each field must only be declared once per scope [$pathStr], ${field.name} " +
                        "at ${TokenListIndex.resolveOrigin(field.origin)} " +
                        "vs ${TokenListIndex.resolveOrigin(other.origin)}"
            )
        }
        fields.add(field)
        return field
    }

    fun ScopeType?.getClassHierarchy(): Int {
        return when (this) {
            null -> -1
            ScopeType.PACKAGE -> 0
            ScopeType.NORMAL_CLASS,
            ScopeType.ENUM_CLASS,
            ScopeType.INTERFACE,
            ScopeType.OBJECT -> 1
            ScopeType.COMPANION_OBJECT -> 2
            ScopeType.ENUM_ENTRY_CLASS -> 3
            ScopeType.INNER_CLASS -> 4
            ScopeType.CONSTRUCTOR,
            ScopeType.FIELD_GETTER,
            ScopeType.FIELD_SETTER,
            ScopeType.INLINE_CLASS,
            ScopeType.METHOD,
            ScopeType.METHOD_BODY,
            ScopeType.MACRO,
            ScopeType.LAMBDA,
            ScopeType.WHEN_CASES,
            ScopeType.WHEN_ELSE,
            ScopeType.VIRTUAL_CLASS -> 6
            ScopeType.TYPE_ALIAS, ScopeType.FIELD -> 7
        }
    }

    fun scopeHierarchyIsAllowed(self: ScopeType?, child: ScopeType?): Boolean {
        if (child == null) return true // exception for imports
        if ((self == ScopeType.METHOD_BODY || self == ScopeType.METHOD) && child == ScopeType.NORMAL_CLASS) {
            return true // exception for named classes inside methods
        }
        if (self == ScopeType.COMPANION_OBJECT && child == ScopeType.NORMAL_CLASS) return true
        if (self == ScopeType.INNER_CLASS && child == ScopeType.COMPANION_OBJECT) return true
        if (self == ScopeType.COMPANION_OBJECT && child == ScopeType.COMPANION_OBJECT) return false // only one is allowed
        return self.getClassHierarchy() <= child.getClassHierarchy()
    }

    fun generate(prefix: String, scopeType: ScopeType): Scope {
        val name = generateName(prefix)
        return put(name, scopeType)
    }

    fun generate(prefix: String, origin: Long, scopeType: ScopeType): Scope {
        val name = generateName(prefix, origin)
        return put(name, scopeType)
    }

    private fun langAlias(name: String): String {
        // hack, because Kotlin forbids us from defining functions inside Kotlin scope
        return if (parent == null && name == "kotlin") "zauber" else name
    }

    fun put(name: String, scopeType: ScopeType?): Scope {
        val name = langAlias(name)
        check(scopeHierarchyIsAllowed(this.scopeType, scopeType)) {
            "$scopeType cannot be placed inside ${this.scopeType} ($pathStr.$name)"
        }

        val child = Scope(name, this)
        child.scopeType = scopeType
        children.add(child)
        if (scopeType == ScopeType.COMPANION_OBJECT) {
            companionObject = child
        }
        return child
    }

    fun getOrPut(name: String, scopeType: ScopeType?): Scope {
        val name = langAlias(name)
        val child = children.firstOrNull { it.name == name }
        if (child != null) {
            // if (child.fileName == null) child.fileName = fileName
            child.mergeScopeTypes(scopeType)
            return child
        }

        return put(name, scopeType)
    }

    fun mergeScopeTypes(scopeType: ScopeType?) {
        val self = this
        if (scopeType != null) {
            if (self.scopeType == null) {
                self.scopeType = scopeType
                if (scopeType == ScopeType.COMPANION_OBJECT) {
                    parent!!.companionObject = this
                }
            } else if (self.scopeType == scopeType) {
                // nothing to do
            } else error("ScopeType conflict in '$pathStr'! ${self.scopeType} vs $scopeType")
        }

        val parentType = parent?.scopeType
        if (!scopeHierarchyIsAllowed(parentType, scopeType)) {
            error("$scopeType cannot be placed inside $parentType} ($pathStr)")
        }
    }

    fun getOrPut(name: String, fileName: String, scopeType: ScopeType?): Scope {
        val child = getOrPut(name, scopeType)
        if (child.fileName == null) child.fileName = fileName
        return child
    }

    fun resolveTypeInner(name: String): Scope? {
        if (name == this.name) return this
        for (child in children) {
            if (child.name == name) return child[ScopeInitType.RESOLVE_TYPES]
        }

        val parent = parent
        if (parent != null && fileName == parent.fileName) {
            val byParent = parent.resolveTypeInner(name)
            if (byParent != null) return byParent
        }

        return null
    }

    fun resolveTypeSameFolder(name: String): Scope? {
        var folderScope = this
        if (fileName == null) {
            // error("No file assigned to $this?")
            return null
        }
        while (folderScope.fileName == fileName) {
            folderScope = folderScope.parent ?: return null
        }
        // println("rtsf[$name,$this] -> $folderScope -> ${folderScope.children.map { it.name }}")
        for (child in folderScope.children) {
            if (child.name == name) return child[ScopeInitType.RESOLVE_TYPES]
        }
        return null
    }

    fun resolveGenericType(name: String): GenericType? {
        declaredTypeParameters.firstOrNull { it.name == name }?.let {
            return GenericType(this, name)
        }
        return parentIfSameFile?.resolveGenericType(name)
    }

    fun resolveTypeOrNull(name: String, astBuilder: ASTBuilderBase): Type? =
        resolveTypeOrNull(name, astBuilder.imports, true)

    fun resolveTypeOrNull(
        name: String, imports: List<Import>,
        searchInside: Boolean
    ): Type? {

        // println("Resolving $name in $this ($searchInside, $fileName, ${parent?.fileName})")

        val parentI = parentIfSameFile
        if (parentI != null && parentI.name == name) {
            return parentI[ScopeInitType.RESOLVE_TYPES].typeWithArgs
        }

        if (searchInside) {
            val insideThisFile = resolveTypeInner(name)
            if (insideThisFile != null) return insideThisFile[ScopeInitType.RESOLVE_TYPES].typeWithArgs
        }

        val genericType = resolveGenericType(name)
        if (genericType != null) return genericType

        for (import in imports) {
            val path = import.path
            if (import.allChildren) {
                // scan all of that scope
                for (child in path.children) {
                    if (child.name == name) {
                        return child[ScopeInitType.RESOLVE_TYPES].typeWithArgs
                    }
                }
            } else if (import.name == name) {
                return path[ScopeInitType.RESOLVE_TYPES].typeWithArgs
            }
        }

        if (pathStr != STDLIB_NAME) {
            val sameFolder = resolveTypeSameFolder(name)
            if (sameFolder != null) return sameFolder[ScopeInitType.RESOLVE_TYPES].typeWithArgs
        }

        // helper at startup / for tests
        val standardType = StandardTypes.standardClasses[name]
        if (standardType != null) return standardType[ScopeInitType.RESOLVE_TYPES].typeWithArgs

        if (pathStr == STDLIB_NAME) {
            val sameFolder = resolveTypeSameFolder(name)
            if (sameFolder != null) return sameFolder[ScopeInitType.RESOLVE_TYPES].typeWithArgs
        }

        if (name == "This" && isClassLike()) {
            return ThisType(typeWithArgs)
        }

        if (name == "Self" && isClassLike()) {
            return SelfType(typeWithArgs)
        }

        // check siblings
        if (parent != null) {
            for (child in parent.children) {
                if (child.name == name) return child[ScopeInitType.RESOLVE_TYPES].typeWithArgs
            }
        }

        // we must also check langScope for any valid paths...
        val langScope = langScope[ScopeInitType.AFTER_DISCOVERY]
        for (child in langScope.children) {
            if (child.name == name) {
                return child[ScopeInitType.RESOLVE_TYPES].typeWithArgs
            }
        }

        return null
    }

    fun resolveType(
        name: String, typeParameters: List<Parameter>,
        functionScope: Scope, astBuilder: ASTBuilderBase,
    ): Type {
        val typeParam = typeParameters.firstOrNull { it.name == name }
        if (typeParam != null) return GenericType(functionScope, typeParam.name)
        return resolveType(name, astBuilder)
    }

    fun resolveType(name: String, astBuilder: ASTBuilderBase): Type {
        return resolveType(name, astBuilder.imports)
    }

    fun resolveType(name: String, imports: List<Import>): Type {
        val name = if (name == "kotlin") "zauber" else name
        return resolveTypeOrNull(name, imports, true)
            ?: error("Unresolved type '$name' in $this, children: ${children.map { it.name }}")
    }

    fun resolveTypeOrNull(name: String, imports: List<Import>): Type? {
        val name = if (name == "kotlin") "zauber" else name
        return resolveTypeOrNull(name, imports, true)
    }

    @Deprecated("Please use the version with origin, if possible")
    fun generateName(prefix: String): String {
        return "$${prefix}_n${nextAnonymousName.incrementAndGet()}"
    }

    /**
     * for inner classes and methods, the origin should be that of the first class-defining keyword, e.g. 'object'
     * */
    fun generateName(prefix: String, uniqueOrigin: Long): String {
        return "$${prefix}_${uniqueOrigin.toString(36)}"
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

    val parentIfSameFileAndVisible: Scope?
        get() {
            val scopeType = scopeType
            return if (
                scopeType != ScopeType.PACKAGE &&
                scopeType != null &&
                parent != null &&
                isVisible(scopeType, parent.scopeType)
            ) {
                parent
            } else null
        }

    fun isVisibleFrom(childScope: Scope): Boolean {
        var self = this
        while (true) {
            if (self == childScope) return true
            self = self.parentIfSameFileAndVisible ?: break
        }
        return false
    }

    fun isVisible(ownScopeType: ScopeType, parentScopeType: ScopeType?): Boolean {
        if (ownScopeType == ScopeType.PACKAGE) return false
        if (parentScopeType == null || parentScopeType.isObjectLike()) return true
        if (ownScopeType == ScopeType.INNER_CLASS) return true
        if (ownScopeType.isClassLike()) return false
        if (ownScopeType.isInsideExpression()) return true
        if (ownScopeType.isMethodLike()) return true
        if (ownScopeType == ScopeType.FIELD) return true
        throw NotImplementedError("isVisible? $ownScopeType > $parentScopeType")
    }

    fun createImmutableField(initialValue: Expression, prefix: String, origin: Long): Field {
        val name = generateName(prefix, origin)
        return addField(
            null, false, isMutable = false, null,
            name, null, initialValue, Flags.NONE, initialValue.origin
        )
    }

    override fun toString(): String =
        style(pathStr.ifEmpty { "ROOT" }, StringStyles.LIGHT_BLUE)

    override fun equals(other: Any?): Boolean {
        if (other is Scope) {
            val root = root
            val otherRoot = other.root
            check(root.atomic >= 0) { "Invalid root '$name', atomic is negative" }
            check(otherRoot.atomic >= 0) { "Invalid root '${otherRoot.atomic}', atomic is negative" }
            check(root === otherRoot) {
                "Root mismatch :(, " +
                        "#${root.atomic} vs " +
                        "#${otherRoot.atomic}, ($pathStr vs ${other.pathStr})"
            }
        }
        return other is Scope && pathStr == other.pathStr
    }

    val atomic = if (parent == null) rootIndex.incrementAndGet() else -1
    val root: Scope by lazy { parent?.root ?: this }

    val selfType: Type by lazy {
        if (isClassLike()) typeWithArgs
        else {
            this[ScopeInitType.AFTER_DISCOVERY] // ensure selfAsMethod is defined
            val selfType = selfAsMethod?.selfType
            selfType ?: parent?.selfType ?: typeWithArgs
        }
    }

    override fun hashCode(): Int {
        var hash = 1
        for (i in path.indices) {
            hash = hash * 31 + path[i].hashCode()
        }
        return hash
    }

    fun isClass(): Boolean = scopeType?.isClass() == true
    fun isClassOrObject() = isClass() || isObject()
    fun isCompanionObject() = scopeType == ScopeType.COMPANION_OBJECT

    /**
     * class | enum | interface | object | package
     * aka hasInstance
     * */
    fun isClassLike(): Boolean = isClass() || isObjectLike()

    /**
     * method, getter, setter or constructor
     * */
    fun isMethodLike(): Boolean = scopeType?.isMethodLike() == true

    fun isConstructor(): Boolean = scopeType?.isConstructor() == true

    /**
     * method, getter or setter
     * */
    fun isMethod(): Boolean = scopeType?.isMethod() == true

    fun isTypeAlias(): Boolean = scopeType == ScopeType.TYPE_ALIAS
    fun isObject(): Boolean = scopeType?.isObject() ?: false
    fun isObjectLike(): Boolean = scopeType?.isObjectLike() ?: false
    fun isInterface(): Boolean = scopeType == ScopeType.INTERFACE
    fun isValueType(): Boolean = flags.hasFlag(Flags.VALUE)
    fun isDataClass(): Boolean = isClass() && flags.hasFlag(Flags.DATA_CLASS)
    fun isPackage(): Boolean = scopeType == ScopeType.PACKAGE
    fun isInnerClass(): Boolean = scopeType == ScopeType.INNER_CLASS
    fun isDataOrValueClass(): Boolean = flags.hasFlag(Flags.DATA_CLASS) || flags.hasFlag(Flags.VALUE)
    fun isLambda(): Boolean = scopeType == ScopeType.LAMBDA

    fun addFlags(flags: FlagSet) {
        this.flags = this.flags or flags
    }

    fun isInsideExpression(): Boolean {
        val scopeType = scopeType ?: return false
        return scopeType.isInsideExpression()
    }

    fun isOpen(): Boolean {
        if (isInterface()) return true
        if (isObjectLike()) return false
        return isClass() && flags.hasAnyFlag(Flags.OPEN or Flags.ABSTRACT)
    }

    fun isInnerClassOf(ownerScope: Scope): Boolean {
        if (scopeType != ScopeType.INNER_CLASS) return false
        val parent = parent!!
        if (parent == ownerScope) return true
        return parent.isInnerClassOf(ownerScope)
    }

    fun isInsideOf(scope: Scope): Boolean {
        var self = this
        while (true) {
            self = self.parent ?: return false
            if (self == scope) return true
        }
    }

    fun isAbstractClass(): Boolean {
        return isInterface() || (isClass() && flags.hasFlag(Flags.ABSTRACT))
    }

    @Deprecated("Avoid this function, other things may already depend on this scope existing")
    fun removeFromParent() {
        parent?.children?.remove(this)
    }

    companion object {
        private val nextAnonymousName = AtomicInteger(0)
        private val rootIndex = AtomicInteger(0)
    }

}
