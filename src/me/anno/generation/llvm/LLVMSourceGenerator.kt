package me.anno.generation.llvm

import me.anno.generation.InheritanceTable
import me.anno.generation.InheritanceTable.Companion.writeLE64
import me.anno.generation.Specializations.specialization
import me.anno.generation.c.CSourceGenerator
import me.anno.generation.c.CSourceGenerator.Companion.hashMethodParameters
import me.anno.generation.java.JavaSourceGenerator
import me.anno.utils.ByteArrayOutputStream2
import me.anno.utils.FullMap
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.expression.CompareType
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isUnsigned
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.SimpleMerge
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.constants.SimpleSpecialValue
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Specialization.Companion.noSpecialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File
import kotlin.math.max

/**
 * generate LLVM directly instead of calling into C/C++, should be faster,
 * but make our life a little harder
 * */
class LLVMSourceGenerator : JavaSourceGenerator() {

    companion object {

    }

    var nextRegisterId = 0
    var nextLabelId = 0

    fun nextReg(): String = "%r${nextRegisterId++}"
    fun nextLabel(prefix: String): String = "${prefix}_${nextLabelId++}"

    val globalNames = ArrayList<String>()
    val globalStructs = ArrayList<LLVMStruct>()
    val objectGlobals = HashMap<Scope, Int>()
    val renames = HashMap<SimpleField, String>()
    val fieldBranches = HashMap<SimpleField, String>()
    val stringByOffset = HashMap<String, Int>()
    var currBranch = "?"

    private val stringBuffer = ByteArrayOutputStream2()

    lateinit var objectGetters: Map<Scope, Int>
    lateinit var objects: List<Specialization>

    lateinit var inheritanceTable: InheritanceTable

    fun defineObjectGetters(data: DependencyData) {
        // find constructed object-likes
        val objects = data.createdClasses.filter { it.clazz.isObjectLike() }
        val getters = HashMap<Scope, Int>(objects.size)
        for (i in objects.indices) {
            getters[objects[i].clazz] = i
        }
        this.objects = objects
        objectGetters = getters
    }

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {

        inheritanceTable = InheritanceTable(data)

        defineObjectGetters(data)

        comment { builder.append("method imports") }
        builder.append("declare void @stdlibMain()"); nextLine()
        for (method in data.calledMethods) {
            if (hasImplementation(method)) continue
            appendMethodImport(method)
        }
        builder.append("declare ptr @calloc(i64, i64)")
        nextLine()
        builder.append("declare ptr @getString(i32)")
        nextLine()

        val methodImports = builder.toString()
        builder.clear()

        comment { builder.append("main method") }
        appendMainMethodCode(mainMethod)

        comment { builder.append("method implementations") }
        for (method in data.calledMethods) {
            if (!hasImplementation(method)) continue
            appendMethodCode(method)
        }

        comment { builder.append("object getters") }
        for (obj in objects) {
            appendObjectGetterCode(obj.clazz)
        }

        val methodBodies = builder.toString()
        builder.clear()

        writeStructTypes()
        builder.append(methodImports)
        writeObjectGlobals()
        builder.append(methodBodies)

        dst.writeText(builder.toString())
        builder.clear()

        inheritanceTable.generateFiles(dst.parentFile)
        stringBuffer.writeTo(File(dst.parentFile, "data/Strings.bin"))
    }

    fun appendMainMethodCode(mainMethod: Method) {
        builder.append("define i32 @main(i32 %argc, i8** %argv) #0 {")
        indentation++
        nextLine()

        // call stdlibMain(), and handle errors
        builder.append("%stdlibRes = call i32 @stdlibMain()"); nextLine()
        builder.append("%stdlibIsOk = icmp eq i32 %stdlibRes, 0"); nextLine()
        builder.append("br i1 %stdlibIsOk, label %stdlibOk, label %stdlibErr"); nextLine()

        builder.append("stdlibErr:"); nextLine()
        builder.append("ret i32 %stdlibRes"); nextLine()

        builder.append("stdlibOk:")
        nextLine()

        val tmp = getObjectInstanceField(mainMethod.ownerScope)
        val spec = Specialization(mainMethod.memberScope, emptyParameterList())
        callMethod(SimpleGraph(spec), spec, listOf(tmp))
        nextLine()

        builder.append("ret i32 0")
        nextLine()

        dedent()
        indentation--
        builder.append("}")
        nextLine()
    }

    fun getLLVMType(type: Type) = structures.getInnerType(type)

    override fun comment(body: () -> Unit) {
        commentDepth++
        try {
            builder.append(if (commentDepth == 1) ";; " else "(")
            val i0 = builder.length
            body()
            for (i in i0 until builder.length) {
                if (builder[i] == '\n') {
                    builder[i] = '|'
                }
            }
            if (commentDepth == 1) nextLine()
            else builder.append(")")
        } finally {
            commentDepth--
        }
    }

    fun writeStructTypes() {
        for (struct in structures.structs.values) {

            builder.append(struct.typeName)
                .append(" = type { ")

            for ((index, prop) in struct.properties.withIndex()) {
                if (index > 0) builder.append(", ")
                builder.append(prop.llvmType.ir)
            }

            builder.append(" }")
            nextLine()
        }
    }

    // todo we can make globals structs instead of pointers, because globals itself are pointers,
    //  and then store the init-flag within the global

    // we cannot just quit -> removing try-catch
    override fun appendMethods(
        classScope: Scope, className: String,
        methods: Collection<Specialization>, headerOnly: Boolean
    ) {
        for (method0 in methods) {
            val method = method0.method
            if (method !is Method) continue
            if (method.scope.parent != classScope) {
                // an inherited method -> skip, because it's already defined in the parent
                continue
            }

            appendMethod(classScope, className, method0, headerOnly)
        }
    }

    fun appendMethodHeader(method: Specialization) {

        val returnType = getLLVMType(
            method.method.resolveReturnType(method)
        )

        builder.append("define ")
            .append(returnType.ir)
        if (returnType is LLVMType.Ptr && returnType.isValueType) {
            builder.setLength(builder.length - 1) // delete star
        }
        builder.append(" ")
            .append(getMethodName(method)).append("(")

        builder.append(getLLVMType(method.method.ownerScope.typeWithArgs).ir)
            .append(" %this")

        // todo append self-type

        for (param in method.method.valueParameters) {
            builder.append(", ")
            ensureFieldName(param)
            builder.append(getLLVMType(param.type).ir).append(" %")
            appendFieldName(param)
        }

        builder.append(")")
    }

    override fun getMethodName(method0: Specialization): String {
        val base = if (method0.method is Constructor) "_init_" else super.getMethodName0(method0)
        return "@${method0.method.ownerScope.pathStr.replace('.', '_')}_${base}_${hashMethodParameters(method0)}"
    }

    fun appendMethodCode(method0: Specialization) {
        val method = method0.method
        method0.use {
            appendMethodHeader(method0)
            writeBlock {
                val l0 = builder.length
                if (method !is Constructor || method.ownerScope.typeWithArgs2 !in nativeNumbers) {
                    when {
                        isArrayGetter(method0) -> appendArrayGetter(method0)
                        isArraySetter(method0) -> appendArraySetter(method0)
                        else -> {

                            if (method is Constructor) {
                                val classScope = method.ownerScope
                                if (classScope == Types.Array.clazz &&
                                    method.valueParameters.size == 1 &&
                                    method.valueParameters[0].type == Types.Int
                                ) {
                                    appendArrayContentInitialization(method)
                                }
                            }

                            val body = method.body!!
                            val context = ResolutionContext(method.scope, method.selfType, method0, true, null)
                            appendCode(context, method0, body, false)
                        }
                    }
                }

                if (method is Constructor && builder.length == l0) {
                    val suffix = "ret"
                    val i = findSuffixOffset(suffix)
                    if (!builder.startsWith(suffix, i)) {
                        // return is typically missing
                        val instanceReg = if (method.ownerScope == Types.Unit.clazz) "%this"
                        else getObjectInstanceField(Types.Unit.clazz)
                        builder.append("ret ").append(getLLVMType(Types.Unit).ir)
                            .append(" ").append(instanceReg)
                        nextLine()
                    }
                }

                // cleanup
                renames.clear()
                nextLabelId = 0
                nextRegisterId = 0
            }
        }
    }

    fun calculateElementAddress(method0: Specialization): LLVMType {
        val elementType = method0.typeParameters[0]
        val elementLLVM = getLLVMType(elementType)
        val clazz = method0.withScope(method0.method.ownerScope)
        val llvmType = getStruct(clazz)

        // loading content pointer
        builder.append("%dataPtr = getelementptr ")
            .append(llvmType.typeName)
            .append(", ptr %this, i32 0, i32 1")
        nextLine()
        builder.append("%data = load ptr, ptr %dataPtr")
        nextLine()

        // compute data address
        builder.append("%elemPtr = getelementptr ")
            .append(elementLLVM.ir).append(", ptr %data, i32 %index")
        nextLine()

        return elementLLVM
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        builder.append("%size64 = sext i32 %size to i64")
        nextLine()

        val selfType = getStruct(specialization.withScope(constructor.ownerScope))
        val elementType = specialization.typeParameters[0]
        val llvmType = getLLVMType(elementType)
        val size = when (llvmType) {
            LLVMType.I1, LLVMType.I8 -> 1
            LLVMType.I16 -> 2
            LLVMType.I32, LLVMType.F32 -> 4
            LLVMType.I64, LLVMType.F64 -> 8
            else -> 8 // todo for value-structs use their size, for objects use their ptr
        }
        builder.append("%mem = call ptr @calloc(i64 %size64, i64 ").append(size).append(')')
        nextLine()

        builder.append("%dataPtr = getelementptr ").append(selfType.typeName)
            .append(", ptr %this, i32 0, i32 1") // content field
        nextLine()

        builder.append("store ptr %mem, ptr %dataPtr")
        nextLine()
    }

    override fun appendArrayGetter(method0: Specialization) {

        val elementLLVM = calculateElementAddress(method0)

        // load value
        builder.append("%result = load ")
            .append(elementLLVM.ir).append(", ptr %elemPtr")
        nextLine()

        builder.append("ret ").append(elementLLVM.ir)
            .append(" %result")
        nextLine()
    }

    override fun appendArraySetter(method0: Specialization) {

        val elementLLVM = calculateElementAddress(method0)

        // store value
        builder.append("store ")
            .append(elementLLVM.ir).append(" %value, ptr %elemPtr")
        nextLine()

        // return
        val unit = getObjectInstanceField(Types.Unit.clazz)
        builder.append("ret ").append(getLLVMType(Types.Unit).ir)
            .append(' ').append(unit)
        nextLine()
    }

    fun appendObjectGetterCode(objectScope: Scope) {
        noSpecialization.use {
            val returnType = getLLVMType(objectScope.typeWithArgs)

            builder.append("define ")
                .append(returnType.ir).append(" ")
                .append(getObjectGetterName(objectScope))
                .append("() {")
            indentation++
            nextLine()

            appendGetObjectInstanceImpl(objectScope)

            // close body
            dedent()
            indentation--
            builder.append("}")
            nextLine()
        }
    }

    fun appendMethodImport(method0: Specialization) {
        method0.use {
            val method = method0.method
            val returnType0 = method.resolveReturnType(method0)
            val returnType1 = getLLVMType(returnType0)

            builder.append("declare ")
                .append(returnType1.ir)
                .append(" ")
                .append(getMethodName(method0))
                .append("(")

            // self
            builder.append(getLLVMType(method.ownerScope.typeWithArgs).ir)

            // explicit self
            if (method.hasExplicitSelfType) {
                builder.append(", ")
                builder.append(getLLVMType(method.selfType!!))
            }

            for (param in method.valueParameters) {
                builder.append(", ")
                builder.append(getLLVMType(param.type).ir)
            }

            builder.append(")")

            nextLine()
        }
    }

    fun addPercentPrefixToLocalFields(graph: SimpleGraph) {
        for (field in graph.localFields) {
            field.name = "%${field.name}"
        }
    }

    override fun appendCode(
        context: ResolutionContext, method1: Specialization,
        body: Expression, skipSuperCall: Boolean
    ) {
        val graph = ASTSimplifier.simplify(method1)
        if (skipSuperCall) graph.removeSuperCalls()
        prepareGraph(graph)
        addPercentPrefixToLocalFields(graph)

        declareLocalFields(graph)

        // write all code
        val pos0 = builder.length
        val blocks = graph.blocks
        check(blocks.first() == graph.startBlock)
        for (i in blocks.indices) {
            appendBlock(graph, blocks[i])
        }

        appendMissingDeclarations(graph, pos0)
    }

    override fun appendBlock(graph: SimpleGraph, block: SimpleBlock) {
        val instructions = block.instructions
        startNextBlock(block)
        for (i in instructions.indices) {
            val instr = instructions[i]
            appendSimpleInstruction(graph, instr)
        }
        jumpToNextBlock(graph, block)
    }

    private fun startNextBlock(block: SimpleBlock) {
        if (block.id == 0) return // start block cannot be jumped to
        currBranch = "b${block.id}"
        dedent()
        builder.append(currBranch).append(':')
        nextLine()
    }

    private fun jumpToNextBlock(graph: SimpleGraph, block: SimpleBlock) {
        if (block.isBranch) {
            val condition = getSimpleFieldReg(graph, block.branchCondition!!)
            builder.append("  br i1 ").append(condition)
                .append(", label %b").append(block.ifBranch!!.id)
                .append(", label %b").append(block.elseBranch!!.id)
        } else {
            val next = block.nextBranch
            if (next != null) {
                builder.append("br label %b").append(next.id)
            } else {
                builder.append("unreachable")
            }
        }
        nextLine()
    }

    fun appendUnreachable() {
        builder.append("unreachable")
        nextLine()
    }

    override fun appendAssign(graph: SimpleGraph, expression: SimpleAssignment) {
        appendAssign(expression.dst)
    }

    fun appendAssign(dst: SimpleField) {
        if (dst.dst.id < 0) return
        fieldBranches[dst] = currBranch
        val rename = renames[dst]
        check(rename == null) { "Renames cannot be assigned" }
        builder.append("%tmp").append(dst.id).append(" = ")
    }

    override fun appendInstrPrefix(graph: SimpleGraph, expr: SimpleInstruction) {
        if (expr is SimpleAssignment) {
            fieldBranches[expr.dst] = currBranch
        }
    }

    override fun appendInstrSuffix(graph: SimpleGraph, expr: SimpleInstruction) {
        if (expr is SimpleAssignment && expr.dst.type == Types.Nothing) {
            appendUnreachable()
        }
        if (expr !is SimpleNumber && expr !is SimpleGetObject) {
            nextLine()
        }
    }

    fun writeObjectGlobals() {
        comment { builder.append("globals") }
        for ((scope, index) in objectGlobals.entries.sortedBy { it.value }) {
            val struct = getStruct(Specialization.fromSimple(scope))
            builder
                .append(globalNames[index])
                .append(" = global ")
                .append(struct.typeName)
                .append("* null")
            nextLine()
        }
    }

    val structures = LLVMStructures(this, 8)

    fun getStruct(classSpecialization: Specialization): LLVMStruct {
        return structures.getStruct(classSpecialization)
    }

    override fun getClassName(scope: Scope, specialization: Specialization): String {
        return if (scope.isPackage()) scope.pathStr + getPackageName(scope)
        else scope.pathStr + createSpecializationSuffix(specialization)
    }

    class Branch(self: LLVMSourceGenerator) {
        val ifLabel = self.nextLabel("if")
        val elseLabel = self.nextLabel("else")
        val endLabel = self.nextLabel("endif")
    }

    fun ifCondition(branch: Branch, condition: String) {
        builder.append("  br i1 ")
            .append(condition)
            .append(", label %")
            .append(branch.ifLabel)
            .append(", label %")
            .append(branch.elseLabel)
        nextLine()
        nextLine()
    }

    fun ifOrElseBranch(label: String, endLabel: String, run: () -> Unit) {
        builder.append(label).append(":")
        indentation++
        nextLine()

        val prevBranch = currBranch
        currBranch = label
        run()
        currBranch = prevBranch

        indentation--
        builder.append("br label %").append(endLabel)
        nextLine()
        nextLine()
    }

    fun ifBranch(branch: Branch, run: () -> Unit) {
        ifOrElseBranch(branch.ifLabel, branch.endLabel, run)
    }

    fun elseBranch(branch: Branch, run: () -> Unit) {
        ifOrElseBranch(branch.elseLabel, branch.endLabel, run)

        builder.append(branch.endLabel).append(":")
        nextLine()
    }

    fun appendGetObjectInstanceImpl(objectScope: Scope) {
        val struct = getStruct(Specialization.fromSimple(objectScope))
        val globalIndex = objectGlobals.getOrPut(objectScope) {
            globalNames.add("@global_${objectScope.pathStr.replace('.', '_')}")
            globalStructs.add(struct)
            objectGlobals.size
        }

        val globalName = globalNames[globalIndex]
        val loadedGlobal = nextReg()
        builder.append(loadedGlobal)
            .append(" = load ").append(struct.typeName)
            .append("*, ptr ").append(globalName)
        nextLine()

        builder.append("%isNull = icmp eq ")
            .append(struct.typeName).append("*")
            .append(" ").append(loadedGlobal)
            .append(", null")
        nextLine()

        val branch = Branch(this)
        ifCondition(branch, "%isNull")
        ifBranch(branch) {
            val value = allocateStruct(
                struct,
                inheritanceTable.getClassIndex(Specialization.fromSimple(objectScope))
            )
            builder.append("store ").append(struct.typeName)
                .append("* ").append(value)
                .append(", ptr ").append(globalName)
            nextLine()

            val constructor = objectScope
                .getOrCreatePrimaryConstructorScope()
                .selfAsConstructor!!

            callMethod(
                null, Specialization(
                    constructor.memberScope,
                    emptyParameterList()
                ), listOf(value)
            )
            nextLine()

            builder.append("ret ").append(struct.typeName)
                .append("* ").append(value)
            nextLine()
        }
        elseBranch(branch) {
            builder.append("ret ").append(struct.typeName)
                .append("* ").append(loadedGlobal)
            nextLine()
        }
        // just decorative
        builder.append("  ")
        appendUnreachable()
    }

    fun estimateStructSize(struct: LLVMStruct): Int {
        var pos = 0
        var maxAlignment = 4
        for (field in struct.properties) {
            val size = when (field.llvmType) {
                LLVMType.I1, LLVMType.I8 -> 1
                LLVMType.I16 -> 2
                LLVMType.I32, LLVMType.F32 -> 4
                LLVMType.I64, LLVMType.F64 -> 8
                is LLVMType.Struct -> {
                    // todo depends on whether it's a value or a reference
                    8
                }
                is LLVMType.Ptr -> 8
            }
            maxAlignment = max(maxAlignment, size)
            pos = align(pos, size)
            pos += size
        }
        pos = align(pos, maxAlignment)
        return pos
    }

    fun align(pos: Int, size: Int): Int {
        val mod = pos % size
        return if (mod > 0) pos - mod + size else pos
    }

    fun allocateStruct(struct: LLVMStruct, classIndex: Int): String {

        val raw = nextReg()
        val typed = nextReg()

        val size = estimateStructSize(struct)

        builder
            .append(raw)
            .append(" = call ptr @calloc(i64 1, i64 ").append(size)
            .append(")")
        nextLine()

        builder.append(typed)
            .append(" = bitcast ptr ").append(raw)
            .append(" to ").append(struct.typeName).append("*")
        nextLine()

        val classIndexPtr = nextReg()
        builder.append(classIndexPtr).append(" = getelementptr ")
            .append(struct.typeName)
            .append(", ptr ").append(typed)
            .append(", i32 0, i32 0")
        nextLine()
        builder.append("store i32 ").append(classIndex)
            .append(", ptr ").append(classIndexPtr)
        nextLine()

        return typed
    }

    fun getObjectGetterName(objectScope: Scope): String {
        return "@obj_${objectScope.pathStr.replace('.', '_')}"
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        throw NotImplementedError("Use getObjectInstanceField(Types.Unit.clazz) instead")
    }

    fun getObjectInstanceField(objectScope: Scope): String {
        val struct = getStruct(Specialization(objectScope, emptyParameterList()))
        val tmp = nextReg()
        builder.append(tmp).append(" = call ").append(struct.typeName)
            .append("* ")
            .append(getObjectGetterName(objectScope))
            .append("()")
        nextLine()
        return tmp
    }

    fun insideObjectConstructor(graph: SimpleGraph, objectScope: Scope): Boolean {
        return objectScope.getOrCreatePrimaryConstructorScope().selfAsConstructor == graph.method
    }

    override fun prepareGraph(graph: SimpleGraph) {
        graph.findBoxingAndUnboxing()
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(emptyList())
        graph.renumberFields()
    }

    fun getSimpleFieldReg(graph: SimpleGraph, field: SimpleField): String {
        return if (field.isOwnerThis(graph)) {
            "%this"
        } else if (field.isObjectLike()) {
            val objectScope = (field.type as ClassType).clazz
            if (insideObjectConstructor(graph, objectScope)) {
                "%this"
            } else {
                getObjectInstanceField(objectScope)
            }
        } else {
            when (val expr = field.constantRef) {
                is NumberExpression -> stringifyNumber(field.type, expr)
                is SpecialValueExpression -> when (expr.type) {
                    SpecialValue.NULL -> "null"
                    SpecialValue.TRUE -> "1"
                    SpecialValue.FALSE -> "0"
                }
                is StringExpression -> {
                    val addr = stringByOffset.getOrPut(expr.value) {
                        val offset = stringBuffer.size
                        val bytes = expr.value.encodeToByteArray()
                        stringBuffer.writeLE64(0) // address once allocated
                        stringBuffer.write(bytes)
                        offset
                    }
                    "getString($addr)"
                }
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    renames[field] ?: "%tmp${field.id}"
                }
                else -> throw NotImplementedError("Append constant field $expr (${expr.javaClass.simpleName})")
            }
        }
    }

    fun stringifyNumber(type: Type, expr: NumberExpression): String {
        val l0 = builder.length
        appendNumber(type, expr)
        val str = builder.substring(l0)
        builder.setLength(l0)
        return str
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (val typeI = getLLVMType(type)) {
            LLVMType.I8 -> builder.append(expr.asInt.toByte())
            LLVMType.I16 -> builder.append(expr.asInt.toShort())
            LLVMType.I32 -> builder.append(expr.asInt.toInt())
            LLVMType.I64 -> builder.append(expr.asInt)
            LLVMType.F32 -> {
                val l0 = builder.length
                builder.append(expr.asFloat.toFloat())
                // LLVM is freaking weird, not handling exponents properly
                if (builder.indexOf('E', l0) >= 0 ||
                    builder.indexOf("Infinity", l0) >= 0 ||
                    builder.indexOf("NaN", l0) >= 0
                ) {
                    builder.setLength(l0)
                    builder.append("bitcast (i32 ")
                        .append(expr.asFloat.toFloat().toRawBits())
                        .append(" to float)")
                }
            }
            LLVMType.F64 -> {
                val l0 = builder.length
                builder.append(expr.asFloat)
                if (builder.indexOf("Infinity", l0) >= 0 ||
                    builder.indexOf("NaN", l0) >= 0
                ) {
                    builder.setLength(l0)
                    builder.append("bitcast (i64 ")
                        .append(expr.asFloat.toRawBits())
                        .append(" to double)")
                }
            }
            else -> throw NotImplementedError("Append number of type $type -> $typeI")
        }
    }

    override fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        if (field.type is ClassType) {
            // ensure struct is known
            getStruct(Specialization(field.type))
        }
        builder.append(field.name).append(" = ")
            .append("alloca ").append(getLLVMType(field.type).ir)
        nextLine()
    }

    override fun canSkipInstruction(expr: SimpleInstruction): Boolean = false

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {

        if (false) {
            builder.append(";; ").append(expr.javaClass.simpleName)
            nextLine()
        }

        when (expr) {
            is SimpleNumber, is SimpleGetObject -> {} // skip
            is SimpleReturn -> {
                val reg = getSimpleFieldReg(graph, expr.value)
                val type = getLLVMType(expr.value.type)

                if (type is LLVMType.Ptr && type.isValueType) {
                    // remove *, so we pass values, not instances
                    val valueReg = nextReg()
                    builder.append(valueReg).append(" = load ").append(type.ir)
                    builder.setLength(builder.length - 1)
                    builder.append(", ptr ").append(reg)
                    nextLine()

                    builder.append("ret ").append(type.ir)
                    builder.setLength(builder.length - 1)
                    builder.append(" ").append(valueReg)
                } else {
                    builder.append("ret ").append(type.ir)
                        .append(" ").append(reg)
                }
            }
            is SimpleConstructorCall -> {
                appendConstructorCallImpl(graph, expr)
            }
            is SimpleAllocateInstance -> {
                // this allocation is a ClassType, so it cannot be null ever
                val struct = getStruct(Specialization(expr.allocatedType))
                if (!expr.allocatedType.isValue()) {
                    val tmp = nextReg()
                    builder.append(tmp).append(" = call ptr @calloc(i32 1, i32 ").append(estimateStructSize(struct))
                        .append(")")
                    nextLine()

                    val classIndexPtr = nextReg()
                    builder.append(classIndexPtr).append(" = getelementptr ")
                        .append(struct.typeName)
                        .append(", ptr ").append(tmp)
                        .append(", i32 0, i32 0")
                    nextLine()
                    builder.append("store i32 ")
                        .append(inheritanceTable.getClassIndex(expr.specialization))
                        .append(", ptr ").append(classIndexPtr)
                    nextLine()

                    appendAssign(expr.dst)
                    builder.append("bitcast ptr ").append(tmp)
                        .append(" to ").append(struct.typeName).append("*")
                } else {
                    appendAssign(expr.dst)
                    builder.append("alloca ").append(struct.typeName)
                }
            }
            is SimpleMethodCall -> {
                val methodName = expr.methodName
                val done = when (expr.valueParameters.size) {
                    0 -> appendUnaryOperator(graph, expr, methodName)
                    1 -> appendBinaryOperator(graph, expr, methodName)
                    else -> false
                }
                if (!done) {
                    appendCallImpl(graph, expr)
                }
            }
            is SimpleGetLocalField -> {
                if (!expr.field.isInsideMethod) {
                    renames[expr.dst] = expr.field.name
                    builder.append(";; rename: %tmp${expr.dst.id} = ${expr.field.name}")
                } else {
                    appendAssign(graph, expr)
                    builder.append("load ").append(getLLVMType(expr.dst.type).ir)
                        .append(", ptr ").append(expr.field.name)
                }
            }
            is SimpleGetClassField -> {
                if (expr.dst.id < 0) return

                val selfType = expr.self.type as ClassType
                if (selfType !in nativeNumbers) {

                    val tmp = nextReg()
                    val self = getSimpleFieldReg(graph, expr.self)
                    val struct = getStruct(Specialization(selfType))
                    builder.append(tmp).append(" = getelementptr ").append(struct.typeName)
                        .append(", ptr ").append(self)
                        .append(", i32 0, i32 ").append(
                            struct.properties.indexOfFirst { it.field?.name == expr.field.name })
                        .append(" ;; ").append(expr.field)
                    nextLine()

                    appendAssign(graph, expr)
                    builder.append("load ").append(getLLVMType(expr.dst.type).ir)
                        .append(", ptr ").append(tmp)

                } else {
                    // field must be 'content'
                    if (expr.dst.id >= 0) {
                        renames[expr.dst] = "%this"
                        check(expr.field.name == "content")
                        builder.append(";; rename: %tmp${expr.dst.id} = %${expr.field.newName}")
                    }
                }
            }
            is SimpleSetLocalField -> {
                val needsCopy = expr.value.type.needsCopy()
                val valueReg = getSimpleFieldReg(graph, expr.value)
                // todo copy if necessary

                builder.append("store ").append(getLLVMType(expr.value.type).ir)
                    .append(' ')
                    .append(valueReg)
                    .append(", ptr ").append(expr.field.name)
            }
            is SimpleSetClassField -> {
                val needsCopy = expr.value.type.needsCopy()
                val valueReg = getSimpleFieldReg(graph, expr.value)
                // todo copy if necessary

                val selfType = expr.self.type as ClassType
                val tmp = nextReg()
                val self = getSimpleFieldReg(graph, expr.self)
                val struct = getStruct(Specialization(selfType))
                builder.append(tmp).append(" = getelementptr ").append(struct.typeName)
                    .append(", ptr ").append(self)
                    .append(", i32 0, i32 ").append(
                        struct.properties.indexOfFirst { it.field?.name == expr.field.name }
                    )
                    .append(" ;; ").append(expr.field)
                nextLine()

                builder.append("store ").append(getLLVMType(expr.value.type).ir)
                    .append(" ").append(valueReg)
                    .append(", ptr ").append(tmp)
            }
            is SimpleCompare -> {
                val ownerType = expr.numberType
                val left = getSimpleFieldReg(graph, expr.left)
                val right = getSimpleFieldReg(graph, expr.right)
                appendAssign(expr.dst)
                appendNativeCompare(ownerType, expr.type)
                builder.append(' ').append(getLLVMType(ownerType).ir)
                    .append(' ').append(left).append(", ").append(right)
            }
            is SimpleMerge -> {
                // create phi instruction 😎
                val fromIf = getSimpleFieldReg(graph, expr.ifField)
                val fromElse = getSimpleFieldReg(graph, expr.elseField)
                val ifBranch = fieldBranches[expr.ifField]
                    ?: error("Missing name for ifBranch ${expr.ifField}")
                val elseBranch = fieldBranches[expr.elseField]
                    ?: error("Missing name for elseBranch ${expr.elseField}")
                appendAssign(expr.dst)
                builder.append("phi ")
                    .append(getLLVMType(expr.dst.type).ir)
                    .append(" [ ").append(fromIf).append(", %").append(ifBranch)
                    .append(" ], [ ").append(fromElse).append(", %").append(elseBranch)
                    .append(" ]")
            }
            is SimpleCheckEquals -> {
                val type = expr.left.type
                assertEquals(type, expr.right.type)
                check(type in nativeNumbers)

                val left = getSimpleFieldReg(graph, expr.left)
                val right = getSimpleFieldReg(graph, expr.right)

                appendAssign(expr.dst)
                // icmp eq i64 %d, 0
                builder.append(if (type.isFloat()) "fcmp" else "icmp")
                    .append(if (expr.negated) " ne " else " eq ")
                    .append(getLLVMType(type).ir)
                    .append(left).append(", ").append(right)
            }
            is SimpleSpecialValue -> {
                // should this not be a constant?
                when (expr.type) {
                    SpecialValue.TRUE -> builder.append("1")
                    SpecialValue.FALSE -> builder.append("0")
                    SpecialValue.NULL -> builder.append("0") // is this right?
                }
            }
            else -> throw NotImplementedError("Implement writing $expr (${expr.javaClass.simpleName})")
        }
    }

    private fun getIntSize(type: LLVMType): Int {
        return when (type) {
            LLVMType.I8 -> 1
            LLVMType.I16 -> 2
            LLVMType.I32 -> 4
            LLVMType.I64 -> 8
            else -> -1
        }
    }

    private fun getFloatSize(type: LLVMType): Int {
        return when (type) {
            LLVMType.F32 -> 4
            LLVMType.F64 -> 8
            else -> -1
        }
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = resolveType(expr.thisInstance.type)
        return if (isCast(methodName) && type in nativeNumbers) {

            val inType = getLLVMType(type)
            val outType0 = expr.dst.type
            val outType = getLLVMType(outType0)

            if (inType == outType) {
                renames[expr.dst] = "%tmp${expr.thisInstance.id}"
                builder.append(";; rename-cast: %tmp${expr.dst.id} = %tmp${expr.thisInstance.id}")
                return true
            }

            val input = getSimpleFieldReg(graph, expr.thisInstance)

            val inSize = getIntSize(inType)
            val outSize = getIntSize(outType)

            val inInt = inSize >= 1
            val outInt = outSize >= 1

            val symbol = when {
                inInt && outInt -> {
                    check(inSize != outSize)
                    when {
                        inSize > outSize -> "trunc"
                        outType0.isUnsigned() -> "zext"
                        else -> "sext"
                    }
                }
                inInt -> {
                    // int to float
                    if (type.isUnsigned()) "uitofp" else "sitofp"
                }
                outInt -> {
                    // float to int
                    if (outType0.isUnsigned()) "fptoui" else "fptosi"
                }
                else -> {
                    // float to float
                    if (getFloatSize(inType) > getFloatSize(outType)) "fptrunc" else "fpext"
                }
            }

            appendAssign(graph, expr)
            builder.append(symbol).append(' ')
                .append(inType.ir).append(' ')
                .append(input).append(" to ")
                .append(outType.ir)

            true
        } else if (methodName == "not" && type == Types.Boolean) {
            val input = getSimpleFieldReg(graph, expr.thisInstance)
            appendAssign(graph, expr)
            builder.append("xor i1 ").append(input).append(", true")
            nextLine()
            true
        } else if (methodName == "inv" && type in nativeNumbers) {
            val input = getSimpleFieldReg(graph, expr.thisInstance)
            appendAssign(graph, expr)
            builder.append("xor  ").append(getLLVMType(type).ir)
            builder.append(' ').append(input).append(", -1")
            nextLine()
            true
        } else false
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = resolveType(expr.thisInstance.type)
        if (type !in nativeNumbers) return false

        val symbol = when (methodName) {
            "plus" -> if (type.isFloat()) "fadd" else "add"
            "minus" -> if (type.isFloat()) "fsub" else "sub"
            "times" -> if (type.isFloat()) "fmul" else "mul"
            "div" -> if (type.isFloat()) "fdiv" else if (type.isUnsigned()) "udiv" else "sdiv"
            "rem" -> if (type.isFloat()) "fmod" /* todo does this exist? */ else if (type.isUnsigned()) "umod" else "smod"
            "and" -> "and"
            "or" -> "or"
            "xor" -> "xor"
            "shl" -> "shl"
            "shr" -> "shr"
            "ushr" -> "ushr"
            "rotateLeft" -> "rotl"
            "rotateRight" -> "rotr"
            else -> return false
        }

        val v0 = getSimpleFieldReg(graph, expr.thisInstance)
        val v1 = getSimpleFieldReg(graph, expr.valueParameters[0])

        val needsCastToI32 = when (type) {
            Types.Byte, Types.UByte,
            Types.Short, Types.UShort -> when (methodName) {
                "and", "or", "xor" -> false
                else -> true
            }
            else -> false
        }

        val unsigned = type.isUnsigned()
        val llvmType = getLLVMType(type).ir

        if (needsCastToI32) {

            val i0 = nextReg()
            val i1 = nextReg()

            // zext = zero-extend, sext = sign-extend
            val castOp = if (unsigned) " = zext " else " = sext "
            builder.append(i0).append(castOp)
                .append(llvmType).append(' ').append(v0).append(" to i32"); nextLine()
            builder.append(i1).append(castOp)
                .append(llvmType).append(' ').append(v1).append(" to i32"); nextLine()

            appendAssign(expr.dst)
            builder
                .append(symbol).append(" i32 ")
                .append(i0).append(", ").append(i1)

        } else {
            appendAssign(expr.dst)
            builder
                .append(symbol).append(' ')
                .append(llvmType)
                .append(' ').append(v0).append(", ").append(v1)
        }
        return true
    }

    fun appendNativeCompare(valueType: Type, compareType: CompareType) {
        val compareName = when (compareType) {
            CompareType.LESS -> "lt"
            CompareType.LESS_EQUALS -> "le"
            CompareType.GREATER -> "gt"
            CompareType.GREATER_EQUALS -> "ge"
        }
        val prefix = when {
            isNumberFloat(valueType) -> "fcmp u" // u = unordered = either value may be NaN
            isNumberSigned(valueType) -> "icmp s" // signed
            else -> "icmp u" // unsigned
        }
        builder.append(prefix)
            .append(compareName)
    }

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        // todo when we implement everything correctly, we do not need this complex copy function
        val valueType = valueType as ClassType
        val method = valueType.clazz.methods
            .firstOrNull { it.name == "copy" && it.valueParameters.isEmpty() }
            ?: error("Missing fun $valueType.copy()")
        val methodSpec = Specialization(method.scope, valueType.typeParameters ?: emptyParameterList())
        callMethod(graph, methodSpec, emptyList())
        nextLine()
    }

    override fun appendCallImpl(graph: SimpleGraph, expr: SimpleMethodCall) {
        val args = (listOf(expr.thisInstance) + expr.valueParameters).map {
            getSimpleFieldReg(graph, it)
        }

        if (expr.methods !is FullMap) {
            val options = inheritanceTable.createSwitchList(expr.specialization)
            if (options.size < 2) {
                val specialization = if (options.isNotEmpty()) options.first().second else expr.specialization
                appendAssign(graph, expr)
                callMethod(graph, specialization, args)
                return
            } else if (options.size <= CSourceGenerator.INHERITANCE_SWITCH_LIMIT) {
                appendInheritedCallSwitch(graph, expr, options, args)
                return
            }
        }

        appendAssign(graph, expr)
        callMethod(graph, expr.specialization, args)
    }

    fun appendInheritedCallSwitch(
        graph: SimpleGraph,
        expr: SimpleMethodCall,
        options: List<Pair<Specialization, Specialization>>,
        args: List<String>
    ) {
        val selfType = expr.thisInstance.type as ClassType
        val struct = getStruct(Specialization(selfType))

        val classIndexPtr = nextReg()
        val classIndexReg = nextReg()
        builder.append(classIndexPtr).append(" = getelementptr ")
            .append(struct.typeName)
            .append(", ptr ").append(args.first())
            .append(", i32 0, i32 0")
        nextLine()

        builder.append(classIndexReg).append(" = load i32, ptr ")
            .append(classIndexPtr)
        nextLine()

        val endLabel = nextLabel("call_end")
        val caseLabels = options.indices.map { nextLabel("case") }
        val returnType = getLLVMType(expr.specialization.method.resolveReturnType(expr.specialization))

        builder.append("switch i32 ").append(classIndexReg)
            .append(", label %").append(caseLabels.last())
            .append(" [")
        nextLine()

        for (i in 0 until options.lastIndex) {
            val (clazz, _) = options[i]
            builder.append("  i32 ").append(inheritanceTable.getClassIndex(clazz))
                .append(", label %").append(caseLabels[i])
            nextLine()
        }

        builder.append("]")
        nextLine()

        val branchResults = ArrayList<String>(options.size)
        for (i in options.indices) {
            val (_, method) = options[i]
            builder.append(caseLabels[i]).append(":")
            nextLine()

            val resultReg = nextReg()
            branchResults += resultReg
            builder.append(resultReg).append(" = ")
            callMethod(graph, method, args)
            nextLine()
            builder.append("br label %").append(endLabel)
            nextLine()
        }

        builder.append(endLabel).append(":")
        nextLine()
        currBranch = endLabel
        appendAssign(graph, expr)
        builder.append("phi ").append(returnType.ir)
        for (i in branchResults.indices) {
            if (i > 0) builder.append(", ")
            builder.append("[ ").append(branchResults[i]).append(", %")
                .append(caseLabels[i]).append(" ]")
        }
    }

    fun appendConstructorCallImpl(graph: SimpleGraph, expr: SimpleConstructorCall) {
        val args = (listOf(expr.thisInstance) + expr.valueParameters)
            .map { getSimpleFieldReg(graph, it) }
        callMethod(graph, expr.specialization, args)
    }

    fun callMethod(
        graph: SimpleGraph?,
        method0: Specialization,
        args: List<String>
    ) {

        val method = method0.method

        val returnType = getLLVMType(method.resolveReturnType(method0))

        builder.append("call ")
            .append(returnType.ir)
            .append(" ").append(getMethodName(method0))
            .append("(")

        val methodParams = ArrayList<LLVMType>()
        method0.use { // <- for resolving types
            if (graph != null || args.isNotEmpty()) {
                methodParams.add(getLLVMType(method.ownerScope.typeWithArgs))
            }

            if (method.hasExplicitSelfType) {
                methodParams.add(getLLVMType(method.selfType!!))
            }

            for (param in method.valueParameters) {
                methodParams.add(getLLVMType(param.type))
            }
        }

        for (i in args.indices) {
            if (i > 0) builder.append(", ")
            builder.append(methodParams[i].ir).append(" ").append(args[i])
        }

        builder.append(")")
    }

}
