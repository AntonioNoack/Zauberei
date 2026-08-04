package me.anno.generation

import me.anno.utils.IntArrayList
import me.anno.utils.StdlibLoader.loadLazyCode
import me.anno.utils.StringStyles.ORANGE
import me.anno.utils.StringStyles.YELLOW
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasAnyFlag
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.expansion.Dependencies
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.TypeResolution
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File
import java.io.OutputStream

class InheritanceTable(val data: DependencyData) {

    companion object {
        private val LOGGER = LogManager.getLogger(InheritanceTable::class)

        val loadInit = loadLazyCode("src/IndirectCall.kt")

        fun OutputStream.writeLE32(data: Int) {
            write(data)
            write(data shr 8)
            write(data shr 16)
            write(data shr 24)
        }

        fun OutputStream.writeLE64(data: Long) {
            writeLE32(data.toInt())
            writeLE32((data shr 32).toInt())
        }
    }

    init {
        // we only need that code, if we enter this class
        loadInit.value
    }

    val helperScope = TypeResolution.langScope
        .getOrPut("inheritance", ScopeType.PACKAGE)

    val interfaceCallSpec by lazy { funToSpec("resolveInterfaceCall") }
    val methodCallSpec by lazy { funToSpec("resolveClassCall") }
    val instanceOfClassCall by lazy { funToSpec("isInstanceOfClass") }
    val instanceOfInterfaceCall by lazy { funToSpec("isInstanceOfInterface") }
    val createStringCall by lazy { funToSpec("createString") }

    // todo build class-call and interface-call table.
    //  class-call:
    //     instance.classIndex, functionIndex -> classCallTable[classIndex]: List<(???)->?>
    //  inheritance-call:
    //     instance.classIndex, interfaceCallIndex -> interfaceTable[classIndex] -> List<Interface>,
    //     Interface = interfaceIndex, (???)->?

    class MethodEntry(val methodIndex: Int, val method: Specialization)
    class ClassEntry(val clazz: Specialization) {
        val methods = ArrayList<MethodEntry>()
    }

    val methodToMethodIndex = HashMap<Specialization, Int>()
    val methodToInterfaceIndex = HashMap<Specialization, Int>()

    private val childrenRecursively = HashMap<Specialization, Collection<Specialization>>()
    private val childImplementations = HashMap<Specialization, Collection<Specialization>>()

    init {
        registerAllMethods()

        if (LOGGER.isInfoEnabled) LOGGER.info("Inheritance: ${methodToMethodIndex.size}x + ${methodToInterfaceIndex.size}x")

        if (methodToMethodIndex.isNotEmpty() || methodToInterfaceIndex.isNotEmpty()) {
            data.calledMethods += interfaceCallSpec
            data.calledMethods += methodCallSpec
            data.calledMethods += funToSpec("readFromClassCallTable")
            data.calledMethods += funToSpec("readFromInterfaceCallTable")
        }

        data.calledMethods += instanceOfClassCall
        data.calledMethods += instanceOfInterfaceCall
        data.calledMethods += funToSpec("readFromSuperClassTable")
        data.calledMethods += funToSpec("readFromClassToInterfaceTable")
        data.calledMethods += funToSpec(Types.Int, "inc")

        data.createdClasses += Specialization(helperScope, emptyParameterList())
        if (Dependencies.collectObjectConstructors) {
            val helperConstr = helperScope.getOrCreatePrimaryConstructorScope()
            data.calledMethods += Specialization(helperConstr, emptyParameterList())
        }
    }

    fun funToSpec(name: String): Specialization {
        val method = helperScope.methods.firstOrNull { it.name == name }
            ?: error("Missing ${style("fun", ORANGE)} $helperScope.${style(name, YELLOW)}()")
        return Specialization(method.memberScope, emptyParameterList())
    }

    fun funToSpec(type: ClassType, name: String): Specialization {
        val method = type.clazz.methods.firstOrNull { it.name == name }
            ?: error("Missing ${style("fun", ORANGE)} $type.${style(name, YELLOW)}()")
        return Specialization(method.memberScope, emptyParameterList())
    }

    fun registerAllMethods() {
        // register all classes and methods, which need indirection
        for (method0 in data.calledMethods.sortedBy { getInheritanceDepth(it) }) {
            val method = method0.method as? Method ?: continue
            when {
                isInterface(method) -> registerInterfaceMethod(method0)
                isOpen(method) -> registerClassMethod(method0)
            }
        }
    }

    private fun getInheritanceDepth(method0: Specialization): Int {
        val method = method0.method as? Method ?: return 0
        var scope = method.ownerScope
        var depth = 1
        while (true) {
            scope[ScopeInitType.AFTER_RESOLVE_TYPES]
            val superCall = scope.superCalls.firstOrNull { it.isClassCall }
                ?: scope.superCalls.firstOrNull { !it.isClassCall }
                ?: return depth
            scope = superCall.type.clazz
            depth++
        }
    }

    private fun isInterface(method: Method): Boolean {
        return method.ownerScope.isInterface() &&
                !method.flags.hasFlag(Flags.FINAL)
    }

    private fun isOpen(method: Method): Boolean {
        return (method.flags.hasAnyFlag(Flags.OPEN or Flags.ABSTRACT)) ||
                (method.flags.hasFlag(Flags.OVERRIDE) && !method.flags.hasFlag(Flags.FINAL))
    }

    private fun registerInterfaceMethod(method0: Specialization) {
        registerMethod(method0, methodToInterfaceIndex)
    }

    private fun registerClassMethod(method0: Specialization) {
        registerMethod(method0, methodToMethodIndex)
    }

    private fun registerMethod(method0: Specialization, methodToMethodIndex: HashMap<Specialization, Int>) {
        var methodIndex = methodToMethodIndex[method0]
        if (methodIndex != null) return
        methodIndex = methodToMethodIndex.size

        val method = method0.method as Method
        val owner = method0.withScope(method.ownerScope)
        val children = childrenRecursively.getOrPut(owner) {
            getChildrenRecursively(owner)
        }

        if (LOGGER.isInfoEnabled) LOGGER.info("children for $method0: $children")

        val implementations = collectChildImplementations(method)
        val adapted = children.mapNotNull { child ->
            val impl = implementations[child.scope!!]
            //   ?: error("Missing implementation for $method in $child")
            if (impl != null) {
                val joinedTypeParams = method0 + child
                joinedTypeParams.withScope(impl.scope)
            } else null
        }

        childImplementations[method0] = adapted

        for (child in adapted) {
            // todo only register, if child classes are known
            //  otherwise, we can just always call the method directly
            //  but register in the table anyway...
            methodToMethodIndex[child] = methodIndex
        }
    }

    private fun collectChildImplementations(method0: Method): Map<Scope, Method> {
        val result = HashMap<Scope, Method>()
        fun collect(method0: Method) {
            val owner = method0.ownerScope[ScopeInitType.ADD_OVERRIDES]
            if (result.put(owner, method0) != null) return

            for (child in method0.childMethods) {
                collect(child as Method)
            }
        }
        collect(method0)
        return result
    }

    private fun getChildrenRecursively(ownerClass: Specialization): Collection<Specialization> {
        val children = HashSet<Specialization>()
        fun addChild(child: Specialization) {
            if (children.add(child)) {
                val grandChildren = data.childClasses[child] ?: return
                for (grandChild in grandChildren) {
                    addChild(grandChild)
                }
            }
        }
        addChild(ownerClass)
        return children
    }

    fun countImplementations(method0: Specialization): Int {
        return childImplementations[method0]?.size ?: 1
    }

    fun getMethodOwner(method: Specialization): Specialization {
        val ownerScope = method.scope!!.parent!!
        return method.withScope(ownerScope)
    }

    fun getMethodOwnerType(method: Specialization): ClassType {
        val ownerScope = method.scope!!.parent!!
        return ownerScope.typeWithArgs.specialize(method) as ClassType
    }

    /**
     * todo many classes may share the same implementation...
     * returns List<(Class, Method)>
     * */
    fun createSwitchList(method0: Specialization): List<Pair<Specialization, Specialization>> {
        val options = childImplementations[method0] ?: return emptyList()
        return options.mapNotNull { method ->
            val clazz = getMethodOwner(method)
            if (isDirectlyConstructable(clazz)) {
                clazz to method
            } else null
        }
    }

    fun isDirectlyConstructable(clazz: Specialization): Boolean {
        val scope = clazz.scope!!
        return !scope.isInterface() && !scope.flags.hasFlag(Flags.ABSTRACT)
    }

    private val classes = ArrayList<Specialization>()
    val classIndices = HashMap<Specialization, Int>()

    init {
        // these are used by low-level implementations like C to create constant strings
        assertEquals(0, getClassIndex(Types.Any))
        assertEquals(1, getClassIndex(Types.String))
        assertEquals(2, getClassIndex(Types.Array.withTypeParameter(Types.Byte)))
    }

    fun getClassIndex(clazz: Specialization): Int {
        check(clazz.isClassLike())
        return classIndices.getOrPut(clazz) {
            val superCall = findSuperCall(clazz.scope!!)
            if (superCall != null) {
                val superType = clazz.getSuperType(superCall)
                getClassIndex(superType)
            }

            classes.add(clazz)
            classIndices.size
        }
    }

    private fun findSuperCall(scope: Scope): SuperCall? {
        return scope.superCalls
            .firstOrNull { it.isClassCall }
    }

    fun getClassIndex(type: ClassType): Int {
        return getClassIndex(Specialization(type))
    }

    fun getClassCallIndex(method0: Specialization): Int {
        return methodToMethodIndex[method0]!!
    }

    fun getInterfaceCallIndex(method0: Specialization): Int {
        return methodToInterfaceIndex[method0]!!
    }

    fun buildClassCallTable(): IntArrayList {
        val builder = IntArrayList(classes.size * 3)
        repeat(classes.size) { builder.add(0) }
        for (i in classes.indices) {
            builder[i] = builder.size
            val clazz = classes[i]

            // todo collect all method indices
        }
        return builder
    }

    fun buildInterfaceCallTable(): IntArrayList {
        val builder = IntArrayList(classes.size * 3 + 1)
        repeat(classes.size + 1) { builder.add(0) }
        for (i in classes.indices) {
            builder[i] = builder.size
            val clazz = classes[i]

            // todo collect all interface indices
        }
        builder[classes.size] = builder.size
        return builder
    }

    fun buildSuperClassTable(): IntArrayList {
        val builder = IntArrayList(classes.size)
        for (i in classes.indices) {
            val superCall = findSuperCall(classes[i].clazz)
            if (superCall != null) {
                val superType = classes[i].getSuperType(superCall)
                val si = getClassIndex(superType)
                check(si < i) { "Class indices must be sorted: super must be first" }
                builder.add(si)
            } else {
                builder.add(-1)
            }
        }
        check(builder.size == classes.size)
        return builder
    }

    fun buildClassToInterfaceTable(): IntArrayList {
        val builder = IntArrayList(classes.size * 3 + 1)
        repeat(classes.size + 1) { builder.add(0) }
        for (i in classes.indices) {
            builder[i] = builder.size

            val clazz = classes[i]
            for (call in clazz.clazz.superCalls) {
                if (call.isClassCall) continue
                val superType = classes[i].getSuperType(call)
                builder.add(getClassIndex(superType))
            }
        }
        builder[classes.size] = builder.size
        return builder
    }

    fun generateFiles(dst: File) {
        val folder = File(dst.parentFile, "data"); folder.mkdirs()
        writeFile(File(folder, "classCallTable.bin"), buildClassCallTable())
        writeFile(File(folder, "interfaceCallTable.bin"), buildInterfaceCallTable())
        writeFile(File(folder, "superClassTable.bin"), buildSuperClassTable())
        writeFile(File(folder, "classToInterfaceTable.bin"), buildClassToInterfaceTable())
    }

    private fun writeFile(dst: File, data: IntArrayList) {
        dst.outputStream().buffered().use {
            for (i in 0 until data.size) {
                it.writeLE32(data[i])
            }
        }
    }

}