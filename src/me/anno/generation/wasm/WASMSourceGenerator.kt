package me.anno.generation.wasm

import me.anno.generation.InheritanceTable
import me.anno.generation.c.CSourceGenerator
import me.anno.generation.c.CSourceGenerator.Companion.hashMethodParameters
import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.wasm.WASMType.Companion.anyRef
import me.anno.utils.CollectionUtils.partitionBy
import me.anno.utils.FullMap
import me.anno.utils.ListOfByteArrays
import me.anno.utils.NumberUtils.getMaxIntValue
import me.anno.utils.NumberUtils.getMinIntValue
import me.anno.utils.NumberUtils.toDoubleCeil
import me.anno.utils.NumberUtils.toDoubleFloor
import me.anno.utils.NumberUtils.toFloatCeil
import me.anno.utils.NumberUtils.toFloatFloor
import me.anno.utils.NumberUtils.toInt
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.reverse.*
import me.anno.zauber.ast.rich.expression.CompareType
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isUnsigned
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isNullable
import me.anno.zauber.ast.simple.SimpleBlock.Companion.needsCopy
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.SimpleMerge
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Specialization.Companion.noSpecialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File

/**
 * directly encode binary WASM
 * like with generating JVM bytecode, we should convert simple-fields back to a stack, where possible -> not really necessary
 * */
class WASMSourceGenerator : JavaSourceGenerator() {

    companion object {
        private val inFloatInstr = arrayOf(
            WASMOpcode.I32S_TRUNC_F32 to "i32.trunc_f32_s",
            WASMOpcode.I32U_TRUNC_F32 to "i32.trunc_f32_u",
            WASMOpcode.I64S_TRUNC_F32 to "i64.trunc_f32_s",
            WASMOpcode.I64U_TRUNC_F32 to "i64.trunc_f32_u",
            WASMOpcode.I32S_TRUNC_F64 to "i32.trunc_f64_s",
            WASMOpcode.I32U_TRUNC_F64 to "i32.trunc_f64_u",
            WASMOpcode.I64S_TRUNC_F64 to "i64.trunc_f64_s",
            WASMOpcode.I64U_TRUNC_F64 to "i64.trunc_f64_u",
        )

        private val outFloatInstr = arrayOf(
            WASMOpcode.F32_CONVERT_I32S to "f32.convert_i32_s",
            WASMOpcode.F32_CONVERT_I32U to "f32.convert_i32_u",
            WASMOpcode.F32_CONVERT_I64S to "f32.convert_i64_s",
            WASMOpcode.F32_CONVERT_I64U to "f32.convert_i64_u",
            WASMOpcode.F64_CONVERT_I32S to "f64.convert_i32_s",
            WASMOpcode.F64_CONVERT_I32U to "f64.convert_i32_u",
            WASMOpcode.F64_CONVERT_I64S to "f64.convert_i64_s",
            WASMOpcode.F64_CONVERT_I64U to "f64.convert_i64_u",
        )
    }

    val functionTypes = HashMap<FunctionType, Int>()
    val typeList = ArrayList<WASMFuncTypeOrStruct>()

    val binary = WASMBinaryWriter()
    val importList = ArrayList<Pair<String, Int>>()
    val exportList = ArrayList<Pair<String, Int>>()

    val functionIndexMap = HashMap<Specialization, Int>()
    val functionIndexList = ArrayList<Specialization>()

    val globalNames = ArrayList<String>()
    val globalStructs = ArrayList<WASMType.Ref>()
    val objectGlobals = HashMap<Scope, Int>()
    lateinit var objectGetters: Map<Scope, Int>
    lateinit var objects: List<Specialization>

    val bodies = ListOfByteArrays(binary.out)

    data class TypeDef(
        val specialization: Specialization,
        val isNullable: Boolean,
        val arrayDepth: Int
    )

    val structs = HashMap<TypeDef, WASMStructLike>()

    // tail-call logic:
    var blockTableDepth = 0
    var currBlockTableIndex = 0
    var nextBlockIdIdx = -1
    lateinit var blockTableOptions: List<SimpleBlock>

    lateinit var inheritanceTable: InheritanceTable

    fun isInlinedMethod(method0: Specialization): Boolean {
        val method = method0.method
        when (method.ownerScope.typeWithArgs2) {
            in nativeNumbers -> {
                if (method.valueParameters.size > 1) return false
                return when (val methodName = method.name) {
                    "plus", "minus", "times", "div", "rem",
                    "equals", "and", "or", "xor", "inv", "neg",
                    "shl", "shr", "ushr", "rotateLeft", "rotateRight" -> true
                    else -> isCast(methodName)
                }
            }
            // array getter and setter are not inlined
            else -> return false
        }
    }

    fun registerMethods(data: DependencyData, mainMethod: Method) {
        // imported methods first
        functionIndexList.addAll(data.calledMethods)
        functionIndexList.removeIf(::isInlinedMethod)
        functionIndexList.partitionBy { method ->
            method.method.body != null ||
                    isArrayGetter(method) || isArraySetter(method)
        }

        var hadImpl = false
        for (i in functionIndexList.indices) {
            val method = functionIndexList[i]
            functionIndexMap[method] = functionIndexMap.size
            if (hasImplementation(method)) {
                hadImpl = true
            } else {
                check(!hadImpl) { "Imports must be first" }
                val methodName = getMethodName(method)
                val funcType = getFunctionTypeIndex(method)
                importList.add(methodName to funcType)
            }
        }

        // register function type for main
        getMainMethodType(mainMethod)
    }

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

        registerMethods(data, mainMethod)
        defineObjectGetters(data)
        indentation++

        comment { builder.append("method imports") }
        for (method in functionIndexList) {
            if (hasImplementation(method)) continue // not imported
            appendMethodImport(method)
        }

        val methodImports = builder.toString()
        builder.clear()

        comment { builder.append("method implementations") }
        for (method in functionIndexList) {
            if (!hasImplementation(method)) continue
            appendMethodCode(method)
        }

        comment { builder.append("object getters") }
        for (obj in objects) {
            appendObjectGetterCode(obj.clazz)
        }

        appendMainMethodCode(mainMethod)

        indentation--

        val methodBodies = builder.toString()
        builder.clear()

        beginModule()
        writeStructTypes()
        writeMethodTypes()
        builder.append(methodImports)
        writeObjectGlobals()
        builder.append(methodBodies)
        endModule()

        dst.writeText(builder.toString())
        builder.clear()

        val start = binary.out.size
        binary.writeModuleHeader()
        binary.writeTypeSection(typeList)
        binary.writeImportSection(importList)
        val functionTypes = functionIndexList.subList(importList.size, functionIndexList.size)
            .map { method -> getFunctionTypeIndex(method) }
        val objectGetterTypes = objects.map { getObjectGetterFunctionTypeIndex(it.scope!!) }
        val implFunctions = functionTypes + objectGetterTypes + getMainMethodType(mainMethod)
        check(implFunctions.size == bodies.size)
        binary.writeFunctionSection(implFunctions)
        binary.writeMemorySection()
        binary.writeGlobalSection(globalStructs)
        binary.writeExportSection(exportList)
        binary.writeCodeSection(bodies)
        binary.out.removeSection(0, start)
    }

    @Suppress("unused")
    fun getMainMethodType(mainMethod: Method): Int {
        // could have args...
        return getFunctionTypeIndex(FunctionType(emptyList(), emptyList()))
    }

    fun appendMainMethodCode(mainMethod: Method) {
        val mainMethodIndex = functionIndexList.size + objects.size
        exportList.add("main" to mainMethodIndex)

        builder.append("(func main (export \"main\") (type $")
            .append(getMainMethodType(mainMethod)).append(")")
        indentation++
        nextLine()

        declareFuncHasNoLocalFields()
        appendGetObjectInstance(mainMethod.ownerScope, root)
        callMethod(Specialization(mainMethod.memberScope, emptyParameterList()))
        nextLine()

        appendDrop()

        dedent()
        indentation--
        builder.append(")")
        nextLine()

        binary.u8(WASMOpcode.END)
        bodies.finishBody()
    }

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

    override fun writeBlock(run: () -> Unit) {
        if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
        builder.append("(")

        indentation++
        nextLine()

        try {
            run()

            dedent()
            indentation--
            builder.append(")\n")
            indent()
            indentation++

        } finally {
            indentation--
        }
    }

    fun beginModule() {
        builder.append("(module")
        indentation++
        nextLine()

        comment { builder.append("memory") }
        val numPages = 64
        builder.append("(import \"js\" \"mem\" (memory ")
            .append(numPages).append("))")
        nextLine()
    }

    fun endModule() {
        dedent()
        builder.append(")")
        indentation--
    }

    fun writeMethodTypes() {
        comment { builder.append("method types") }
        val functionTypeList = typeList
        for (i in functionTypeList.indices) {
            val type = functionTypeList[i] as? FunctionType ?: continue

            builder.append("(type \$t")
                .append(i)
                .append(" (func")

            for (param in type.params) {
                builder.append(" (param ")
                    .append(param.wasmName)
                    .append(')')
            }

            for (result in type.results) {
                builder.append(" (result ")
                    .append(result.wasmName)
                    .append(')')
            }

            builder.append("))")
            nextLine()
        }
    }

    fun writeStructTypes() {
        comment { builder.append("struct types") }
        for (struct in typeList) {
            when (struct) {
                is WASMStruct -> {
                    writeSuperType(struct)
                    writeStructType(struct)
                    if (struct.superType != null) builder.append(")")
                }
                is WASMArray -> {
                    writeSuperType(struct)

                    builder.append(" (array (mut")
                    val elementType = struct.elementStruct
                    if (elementType != null) {
                        writeStructType(elementType)
                    } else {
                        builder.append(' ')
                        builder.append(struct.elementType.wasmName)
                    }
                    builder.append("))")
                    if (struct.superType != null) builder.append(")")
                }
                is FunctionType -> continue
                else -> error("Unknown type")
            }

            nextLine()
        }
    }

    fun writeSuperType(struct: WASMStructLike) {
        builder.append("(type $").append(struct.typeName)
        if (struct.superType != null) {
            builder.append(" (sub $").append(struct.superType.typeName)
        }
    }

    fun writeStructType(struct: WASMStruct) {
        builder.append(" (struct")

        for (property in struct.properties) {
            builder.append(" (mut ") // mut, because we want to zero-initialize them
            builder.append(property.wasmType.wasmName).append(")")
        }

        builder.append("))")
    }

    fun getWASMType(type: Type): WASMType {
        return when (val type = resolveType(type)) {

            Types.Boolean, Types.Char,
            Types.Byte, Types.UByte,
            Types.Short, Types.UShort,
            Types.Int, Types.UInt -> WASMType.I32

            Types.Long, Types.ULong -> WASMType.I64

            Types.Float, Types.Half -> WASMType.F32
            Types.Double -> WASMType.F64

            is ClassType -> getStruct(Specialization(type), false).type
            else -> anyRef
        }
    }

    fun getFunctionTypeIndex(method0: Specialization): Int {
        method0.use {
            val method = method0.method
            method.scope[ScopeInitType.CODE_GENERATION]

            val hasThis = hasThis(method)
            val hasReturn = hasReturn(method)

            val sizeGuess = hasThis.toInt() +
                    method.hasExplicitSelfType.toInt() +
                    method.valueParameters.size

            val params = ArrayList<WASMType>(sizeGuess)

            // this:
            if (hasThis) {
                params.add(getWASMType(method.ownerScope.typeWithArgs))
            }

            // self:
            if (method.hasExplicitSelfType) {
                val selfType = method.selfType!!
                params.add(getWASMType(selfType))
            }

            // params:
            for (param in method.valueParameters) {
                params.add(getWASMType(param.type))
            }

            // todo throwing methods could return the value as an extra return value...
            //  or we finally use WASM exceptions :)

            val returnTypes = if (hasReturn) {
                val returnType = method.resolveReturnType(method0)
                listOf(getWASMType(returnType))
            } else emptyList()
            val functionType = FunctionType(params, returnTypes)
            return getFunctionTypeIndex(functionType)
        }
    }

    fun getFunctionTypeIndex(functionType: FunctionType): Int {
        return functionTypes.getOrPut(functionType) {
            typeList.add(functionType)
            typeList.lastIndex
        }
    }

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

    fun appendMethodHeader(typeIndex: Int, methodName: String, method0: Specialization, export: Boolean) {
        val method = method0.method
        val functionIndex = functionIndexMap[method0]!!
        appendMethodHeader(
            method, typeIndex, methodName, functionIndex, method.hasExplicitSelfType,
            method.valueParameters, export
        )
    }

    fun appendMethodHeader(
        method: MethodLike?,
        typeIndex: Int, methodName: String, functionIndex: Int,
        explicitSelfType: Boolean, valueParameters: List<Parameter>,
        isExportedFunction: Boolean
    ) {
        val type = typeList[typeIndex] as FunctionType

        builder.append("(func $").append(methodName)

        if (isExportedFunction) {
            builder.append(" (export \"").append(methodName).append("\")")
            exportList.add(methodName to functionIndex)
        }

        builder.append(" (type \$t").append(typeIndex).append(")")
        if (type.params.isNotEmpty()) {

            val hasThis = method != null && hasThis(method)

            var j = 0
            if (hasThis) appendParamWithName(type.params[j++], "this")
            if (explicitSelfType) appendParamWithName(type.params[j++], "__self")
            for (i in valueParameters.indices) {
                appendParamWithName(type.params[j + i], valueParameters[i].newName)
            }
        } // else object getter

        // return types
        for (resultType in type.results) {
            builder.append(" (result ").append(resultType.wasmName)
                .append(")")
        }

        indentation++
        nextLine()
    }

    fun appendParamWithName(type: WASMType, name: String) {
        builder.append(" (param \$").append(name)
            .append(' ').append(type.wasmName).append(")")
    }

    override fun getMethodName(method0: Specialization): String {
        val base = if (method0.method is Constructor) "_init_" else super.getMethodName0(method0)
        return "${method0.method.ownerScope.pathStr.replace('.', '_')}_${base}_${hashMethodParameters(method0)}"
    }

    fun appendMethodCode(method0: Specialization) {
        val type = getFunctionTypeIndex(method0)
        val method = method0.method
        method0.use {
            appendMethodHeader(type, getMethodName(method0), method0, true)

            if (method !is Constructor || method.ownerScope.typeWithArgs2 !in nativeNumbers) {
                when {
                    isArrayGetter(method0) -> appendArrayGetter(method0)
                    isArraySetter(method0) -> appendArraySetter(method0)
                    else -> {
                        val body = method.body!!
                        val context = ResolutionContext(
                            method.scope, method.selfType,
                            method0, true, null
                        )
                        appendCode(context, method0, body, false)
                    }
                }
            } else {
                // we would get issues, if we tried to call super(), because 'this' is not a reference
                declareFuncHasNoLocalFields()
            }

            if (method is Constructor && hasReturn(method)) {
                val suffix = "return"
                val i = findSuffixOffset(suffix)
                if (!builder.startsWith(suffix, i)) {
                    // return is typically missing
                    if (method.ownerScope == Types.Unit.clazz) appendGetThis()
                    else appendGetObjectInstance(Types.Unit.clazz, method.scope)
                    // ret() // <- not really necessary
                }
            }

            // close body
            dedent()
            builder.append(")")
            binary.u8(WASMOpcode.END)
            indentation--
            nextLine()

            bodies.finishBody()
        }
    }

    override fun appendArrayGetter(method0: Specialization) {

        declareFuncHasNoLocalFields()

        val classSpec = method0.withScope(Types.Array.clazz)
        val arrayType = getStruct(classSpec, false)
        val contentType = arrayType.properties[1].wasmType as WASMType.Ref

        // load self
        builder.append("local.get 0")
        binary.localGet(0)
        nextLine()

        // convert array-wrapper to content
        builder.append("struct.get ").append(arrayType.typeName).append(" 1")
        binary.structGet(arrayType.typeIndex, 1)
        nextLine()

        // load index
        builder.append("local.get 1")
        binary.localGet(1)
        nextLine()

        builder.append("array.get ").append(contentType.wasmName)
        binary.arrayGet(contentType)
        nextLine()

        val elementType = classSpec.typeParameters[0]
        if (!elementType.isNullable() && elementType !in nativeNumbers) {
            castNonNull()
        }

    }

    override fun appendArraySetter(method0: Specialization) {

        declareFuncHasNoLocalFields()

        val wasmType = getStruct(method0.withScope(Types.Array.clazz), false)
        val contentType = wasmType.properties[1].wasmType as WASMType.Ref

        // load self
        builder.append("local.get 0")
        binary.localGet(0)
        nextLine()

        // convert array-wrapper to content
        builder.append("struct.get ").append(wasmType.typeName).append(" 1")
        binary.structGet(wasmType.typeIndex, 1)
        nextLine()

        // load index
        builder.append("local.get 1")
        binary.localGet(1)
        nextLine()

        // load value
        builder.append("local.get 2")
        binary.localGet(2)
        nextLine()

        builder.append("array.set ").append(contentType.wasmName)
        binary.arraySet(contentType)
        nextLine()

        appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)

    }

    fun getObjectGetterFunctionTypeIndex(objectScope: Scope): Int {
        val returnType = objectScope.typeWithArgs
        val functionType = FunctionType(emptyList(), listOf(getWASMType(returnType)))
        return getFunctionTypeIndex(functionType)
    }

    fun appendObjectGetterCode(objectScope: Scope) {
        val type = getObjectGetterFunctionTypeIndex(objectScope)
        noSpecialization.use {
            appendMethodHeader(
                null, type, getObjectGetterName(objectScope),
                getObjectGetterFunctionIndex(objectScope), false,
                emptyList(), true
            )

            // define struct as local variable; we need it to set classIndex
            val structNullable = getStruct(Specialization.fromSimple(objectScope), isNullable = true)
            builder.append("(local \$obj ").append(structNullable.type.wasmName).append(")")
            nextLine()
            binary.u32(1)
            binary.u32(1)
            binary.writeValueType(structNullable.type)
            appendGetObjectInstanceImpl(objectScope)

            // close body
            dedent()
            builder.append(")")
            binary.u8(WASMOpcode.END)
            indentation--
            nextLine()

            bodies.finishBody()
        }
    }

    fun appendMethodImport(method: Specialization) {
        // (import "env" "print_i32" (func $print_i32 (param i32)))
        val type = getFunctionTypeIndex(method)
        method.use {
            val methodName = getMethodName(method)
            builder.append("(import \"env\" \"").append(methodName).append("\" ")
            appendMethodHeader(type, methodName, method, false)
            removeTrailingWhitespace()
            builder.append("))")
            indentation--
            nextLine()
        }
    }

    override fun appendCode(
        context: ResolutionContext, method1: Specialization,
        body: Expression, skipSuperCall: Boolean
    ) {
        val graph = ASTSimplifier.simplify(method1)
        if (skipSuperCall) graph.removeSuperCalls()
        prepareGraph(graph)

        declareLocalFieldsAsVariables(graph)

        // write all code
        if (graph.hasTailCalls()) appendTailCallCode(graph)
        else appendBlock(graph, graph.startBlock)
    }

    override fun prepareGraph(graph: SimpleGraph) {
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(emptyList())
        graph.removeSimpleGetObject()
        graph.removeMergeInfoInstructions()
        graph.renumberFields()
        // not this one, because WASM is optimized after
        // graph.markSimpleReadImmediatelyAfterAssignment()

        CodeReconstruction.createCodeFromGraph(graph)
        graph.renumberFields() // necessary
    }

    override fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("(local \$nextBlockId i32)"); nextLine()

        i32Const(0); nextLine()
        builder.append("local.set \$nextBlockId")
        binary.localSet(nextBlockIdIdx)
        nextLine()

        beginLoop("blockTable")

        val targets = findTailCallTargets(graph)
        val options = graph.blocks.filterIndexed { index, block -> index == 0 || targets[block.id] }
        blockTableOptions = options

        repeat(options.size + 1) { index ->
            builder.append("(block \$bbt").append(index)
            binary.u8(WASMOpcode.BLOCK)
            binary.u8(0x40) // no results
            indentation++
            blockTableDepth++
            nextLine()
        }

        builder.append("local.get \$nextBlockId")
        binary.localGet(nextBlockIdIdx)
        nextLine()

        // switching-block
        builder.append("br_table")
        binary.u8(WASMOpcode.BR_TABLE)
        binary.u32(options.size) // number of targets
        repeat(options.size) { index ->
            builder.append(" \$bbt").append(index)
            binary.u32(index) // trivial jump target
        }
        binary.u32(0) // default jump target (none)
        indentation--
        nextLine()
        endLoop() // contains blockTableDepth--

        // content blocks
        for (i in options.indices) {
            currBlockTableIndex = i
            appendBlock(graph, options[i])
            indentation--
            dedent()
            endLoop() // contains blockTableDepth--
        }

        indentation--// todo why is this needed???
        dedent()
        endLoop()

        appendUnreachable()
    }

    fun declareFuncHasNoLocalFields() {
        binary.u32(0)
    }

    fun declareLocalFieldsAsVariables(graph: SimpleGraph) {

        val typesForBinary = ArrayList<WASMType>()

        // first all eventually mutable fields
        val localFields = graph.localFields
        for (field in localFields) {
            if (field == graph.thisField ||
                field == graph.selfField ||
                field in graph.parameterFields
            ) continue // already have an ID

            val type = getWASMType(field.type)
            builder.append("(local $").append(field.name)
                .append(' ').append(type.wasmName).append(')')
            typesForBinary.add(type)
            nextLine()
        }

        // then all assign-once fields
        for (field in graph.simpleFields) {
            // if (field.fromLocalField != null) continue // todo remove them, is easy enough
            val type = getWASMType(field.type)
            if (field.mergeInfo == null) {
                builder.append("(local \$tmp").append(field.id)
                    .append(' ').append(type.wasmName).append(')')
                nextLine()
            }
            // needed for now
            typesForBinary.add(type)
        }

        if (graph.hasTailCalls()) {
            val numBuiltIn = 1 + graph.method.hasExplicitSelfType.toInt() + graph.method.valueParameters.size
            nextBlockIdIdx = typesForBinary.size + numBuiltIn
            typesForBinary.add(WASMType.I32)
        } else nextBlockIdIdx = -1

        // we should reorder all field indices -> can do that later
        //  as an optimization step, not now
        binary.u32(typesForBinary.size)
        for (type in typesForBinary) {
            binary.u32(1)
            binary.writeValueType(type)
        }
    }

    override fun appendInstrPrefix(graph: SimpleGraph, expr: SimpleInstruction) {
        // nothing required
    }

    fun appendDrop() {
        builder.append("drop")
        binary.drop()
        nextLine()
    }

    fun appendUnreachable() {
        builder.append("unreachable")
        binary.unreachable()
        nextLine()
    }

    override fun appendAssign(graph: SimpleGraph, expression: SimpleAssignment) {
        val dst = expression.dst.dst
        builder.append("local.set \$tmp").append(dst.id)
        binary.localSet(getSimpleFieldOffset(graph) + dst.id)
        nextLine()
    }

    override fun appendInstrSuffix(graph: SimpleGraph, expr: SimpleInstruction) {
        if (expr is SimpleAllocateInstance) {
            // store instance, but we must also set the class index

            val dst = expr.dst.dst
            val struct = getStruct(Specialization(expr.allocatedType), false)
            builder.append("local.tee \$tmp").append(dst.id)
            binary.localTee(getSimpleFieldOffset(graph) + dst.id)
            nextLine()

            i32Const(inheritanceTable.getClassIndex(expr.specialization))
            nextLine()

            builder.append("struct.set $")
                .append(struct.typeName).append(' ')
                .append(0)
            binary.structSet(struct.typeIndex, 0)
            nextLine()

            return
        }
        if (expr is SimpleAssignment && expr.dst.type != Types.Nothing) {
            val needed = expr.dst.dst.id >= 0
            val available = when (expr) {
                is SimpleConstructorCall -> hasReturn(expr.method)
                is SimpleMethodCall -> hasReturn(expr.sample)
                else -> true
            }
            if (needed) {
                check(available)
                appendAssign(graph, expr)
            } else if (available) {
                builder.append("drop ;; %").append(expr.dst.dst.id).append(" not needed")
                binary.drop()
                nextLine()
            }
        }
        if (expr is SimpleAssignment && expr.dst.type == Types.Nothing) {
            appendUnreachable()
        }
    }

    fun writeObjectGlobals() {
        comment { builder.append("globals") }
        for ((scope, index) in objectGlobals.entries.sortedBy { it.value }) {
            val struct = getStruct(Specialization.fromSimple(scope), true)
            builder.append("(global $")
                .append(globalNames[index])
                .append(" (mut (ref null $")
                .append(struct.typeName)
                .append(")) (ref.null $")
                .append(struct.typeName)
                .append("))")
            nextLine()
        }
    }

    val classIndexProp = WASMProperty(null, WASMType.I32, 0)

    fun getStruct(classSpecialization: Specialization, isNullable: Boolean, arrayDepth: Int): WASMStructLike {
        check(classSpecialization.clazz.isClassLike()) {
            "Invalid struct: $classSpecialization"
        }

        val key = TypeDef(classSpecialization, isNullable, arrayDepth)
        var createdStruct = false
        val s = structs.getOrPut(key) {
            classSpecialization.use {
                if (arrayDepth > 0) {
                    val clazz = classSpecialization.clazz.typeWithArgs2
                    if (clazz in nativeNumbers) {
                        // child is number, get native type instead...
                        val elementType = getWASMType(clazz)
                        val typeName = elementType.wasmName + "Q"
                        WASMArray(
                            null, typeList.size, typeName,
                            null, elementType, false
                        )
                    } else {
                        val elementType = getStruct(classSpecialization, isNullable, arrayDepth - 1)
                        val elementType0 = getStruct(classSpecialization, isNullable)
                        val typeName = elementType.typeName + "Q"
                        WASMArray(
                            null, typeList.size, typeName,
                            elementType0, elementType.type, false
                        )
                    }
                } else {
                    createdStruct = true

                    // damn NodeJS WASM doesn't support references into the future :(,
                    //  so we have to prepare a little
                    val clazz = classSpecialization.scope!!
                    if (clazz == Types.Array.clazz) {
                        val child = Types.Array.typeParameters!![0]
                            .specialize(classSpecialization) as ClassType

                        getStruct(Specialization(child), false, 1)
                    }

                    createStructImpl(classSpecialization, isNullable)
                }.apply {
                    typeList.add(this)
                }
            }
        }

        // create these later, so we can support recursive types
        if (createdStruct) {

            val clazz = classSpecialization.scope!!

            s as WASMStruct
            s.properties.add(classIndexProp)

            if (clazz == Types.Array.clazz) {
                val child = Types.Array.typeParameters!![0]
                    .specialize(classSpecialization) as ClassType

                val type = getStruct(Specialization(child), false, 1)
                // println("array-content type[${type.typeIndex}]: $type")
                s.properties.add(WASMProperty(null, type.type, s.properties.size))
            }

            for (field in clazz.fields) {
                if (!isStoredField(field)) continue
                field.ownerScope[ScopeInitType.AFTER_RESOLVE_TYPES]

                val type = getWASMType(
                    field.valueType
                        ?: error("Missing valueType for $field")
                )
                s.properties += WASMProperty(field, type, s.properties.size)
            }
        }
        return s
    }

    fun getStruct(classSpecialization: Specialization, isNullable: Boolean): WASMStruct {
        return getStruct(classSpecialization, isNullable, 0) as WASMStruct
    }

    private fun createStructImpl(
        classSpecialization: Specialization,
        isNullable: Boolean,
    ): WASMStruct {

        val clazz = classSpecialization.clazz

        val superType = run {
            if (isNullable) {
                getStruct(classSpecialization, false)
            } else {
                val superType0 = classSpecialization.superType
                if (superType0 != null) getStruct(superType0, false) else null
            }
        }

        val typeIndex = typeList.size
        var typeName = getClassName(clazz, classSpecialization)
        if (isNullable) typeName += "?"
        return WASMStruct(superType, typeIndex, typeName, isNullable)
    }

    override fun getClassName(scope: Scope, specialization: Specialization): String {
        return if (scope.isPackage()) getPackageName(scope)
        else scope.pathStr.replace('.', '_') + createSpecializationSuffix(specialization)
    }

    fun appendGetObjectInstanceImpl(objectScope: Scope) {
        val structNullable = getStruct(Specialization.fromSimple(objectScope), isNullable = true)
        val globalIndex = objectGlobals.getOrPut(objectScope) {
            globalNames.add("global_${objectScope.pathStr.replace('.', '_')}")
            globalStructs.add(structNullable.type as WASMType.Ref)
            objectGlobals.size
        }

        globalGet(globalIndex)
        nextLine()

        builder.append("ref.is_null")
        binary.u8(WASMOpcode.REF_IS_NULL)
        nextLine()

        beginIf()

        builder.append("struct.new_default $").append(structNullable.typeName)
        binary.structNewDefault(structNullable.typeIndex)
        nextLine()

        builder.append("local.tee \$obj")
        binary.localTee(0)
        nextLine()

        globalSet(globalIndex)
        nextLine()

        builder.append("local.get \$obj")
        binary.localGet(0)
        nextLine()

        i32Const(inheritanceTable.getClassIndex(Specialization.fromSimple(objectScope)))
        nextLine()

        builder.append("struct.set $").append(structNullable.typeName).append(" 0")
        binary.structSet(structNullable.typeIndex, 0)
        nextLine()

        val constructor = objectScope
            .getOrCreatePrimaryConstructorScope()
            .selfAsConstructor!!

        builder.append("local.get \$obj")
        binary.localGet(0)
        nextLine()

        castNonNull()
        callMethod(Specialization(constructor.memberScope, emptyParameterList()))
        nextLine()

        appendDrop()
        endIfElse()

        globalGet(globalIndex)
        nextLine()

        castNonNull()
    }

    fun castNonNull() {
        builder.append("ref.as_non_null")
        binary.u8(WASMOpcode.REF_AS_NON_NULL)
        nextLine()
    }

    fun getObjectGetterName(objectScope: Scope): String {
        return "obj_${objectScope.pathStr.replace('.', '_')}"
    }

    fun getObjectGetterFunctionIndex(objectScope: Scope): Int {
        val getter = objectGetters[objectScope]
            ?: error("Missing object-getter for $objectScope")
        return functionIndexList.size + getter
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        builder.append("call $").append(getObjectGetterName(objectScope))
        binary.call(getObjectGetterFunctionIndex(objectScope))
        nextLine()
    }

    fun beginIf(resultType: WASMType? = null) {
        builder.append("(if")
        if (resultType != null) {
            builder.append(" (result ").append(resultType.wasmName).append(")")
        }
        builder.append(" (then")
        indentation++
        nextLine()

        binary.u8(WASMOpcode.IF)

        // todo how are multiple results encoded?
        if (resultType == null) binary.u8(0x40) // empty block
        else binary.writeValueType(resultType)

        blockTableDepth++
    }

    fun beginElse() {
        dedent()
        builder.append(") (else")
        nextLine()

        binary.u8(WASMOpcode.ELSE)
    }

    fun endIfElse() {
        dedent()
        builder.append("))")
        indentation--
        nextLine()

        binary.u8(WASMOpcode.END)
        blockTableDepth--
    }

    fun globalGet(globalIndex: Int) {
        builder.append("global.get $").append(globalNames[globalIndex])
        binary.globalGet(globalIndex)
    }

    fun globalSet(globalIndex: Int) {
        builder.append("global.set $").append(globalNames[globalIndex])
        binary.globalSet(globalIndex)
    }

    fun insideObjectConstructor(graph: SimpleGraph, objectScope: Scope): Boolean {
        return objectScope.getOrCreatePrimaryConstructorScope().selfAsConstructor == graph.method
    }

    fun getSimpleFieldOffset(graph: SimpleGraph): Int {
        return graph.localFields.size
    }

    fun appendGetThis() {
        builder.append("local.get \$this")
        binary.localGet(0)
        nextLine()
    }

    fun appendGetField(graph: SimpleGraph, field: SimpleField) {
        if (field.isOwnerThis(graph)) {
            appendGetThis()
        } else if (field.isObjectLike()) {
            val objectScope = (field.type as ClassType).clazz
            if (insideObjectConstructor(graph, objectScope)) {
                appendGetThis()
            } else {
                appendGetObjectInstance(objectScope, graph.method.scope)
            }
        } else {
            val field = field.dst
            when (val expr = field.constantRef) {
                is NumberExpression -> appendNumber(field.type, expr)
                is SpecialValueExpression -> when (expr.type) {
                    SpecialValue.NULL -> {
                        builder.append("ref.null")
                        binary.u8(WASMOpcode.REF_NULL)
                    }
                    SpecialValue.TRUE -> i32Const(1)
                    SpecialValue.FALSE -> i32Const(0)
                }
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    val localField = field.fromLocalField
                    if (localField != null) {
                        builder.append("local.get \$").append(localField.name)
                        binary.localGet(localField.id)
                        nextLine()
                    } else {
                        builder.append("local.get \$tmp").append(field.id)
                        binary.localGet(getSimpleFieldOffset(graph) + field.id)
                        nextLine()
                    }
                }
                else -> throw NotImplementedError("Append constant field $expr")
            }
        }
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (val wasmType = getWASMType(type)) {
            WASMType.I32 -> i32Const(expr.asInt.toInt())
            WASMType.I64 -> i64Const(expr.asInt)
            WASMType.F32 -> f32Const(expr.asFloat.toFloat())
            WASMType.F64 -> f64Const(expr.asFloat)
            else -> throw NotImplementedError("Append number of type $type -> $wasmType")
        }
        nextLine()
    }

    fun i32Const(value: Int) {
        builder.append("i32.const ").append(value)
        binary.i32Const(value)
    }

    fun i64Const(value: Long) {
        builder.append("i64.const ").append(value)
        binary.i64Const(value)
    }

    fun f32Const(value: Float) {
        builder.append("f32.const ").append(value)
        binary.f32Const(value)
    }

    fun f64Const(value: Double) {
        builder.append("f64.const ").append(value)
        binary.f64Const(value)
    }

    fun ret() {
        builder.append("return")
        binary.ret()
        nextLine()
    }

    fun continueLoop(name: String, jumpDepth: Int) {
        builder.append("br $").append(name).append(" ;; ").append(jumpDepth)
        binary.u8(WASMOpcode.BR)
        binary.u8(jumpDepth) // how many extra loops/blocks are broken
        indentation--
        nextLine()
    }

    fun beginLoop(name: String) {
        builder.append("(loop $").append(name)
        binary.u8(WASMOpcode.LOOP)
        binary.u8(0x40) // empty
        indentation++
        nextLine()
        blockTableDepth++
    }

    fun endLoop() {
        builder.append(')')
        binary.u8(WASMOpcode.END)
        nextLine()
        blockTableDepth--
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleNumber -> appendNumber(expr.dst.type, expr.base)
            is SimpleBranch -> {
                appendGetField(graph, expr.condition)
                beginIf()
                /**/appendBlock(graph, expr.ifTrue)
                if (expr.ifFalse != null) {
                    beginElse()
                    appendBlock(graph, expr.ifFalse)
                }
                endIfElse()
            }
            is SimpleLoop -> {
                val name = "b${expr.body.id}"
                if (expr.condition == null) {

                    beginLoop(name)
                    appendBlock(graph, expr.body)
                    continueLoop(name, 0)
                    endLoop()

                    appendUnreachable()
                    // nothing else can come after

                } else {

                    beginLoop(name)
                    appendBlock(graph, expr.conditionBlock!!)
                    appendGetField(graph, expr.condition)

                    beginIf()
                    if (expr.negate) beginElse()
                    /**/appendBlock(graph, expr.body)
                    /**/continueLoop(name, 1)
                    endIfElse()

                    endLoop()
                    // stuff may come after
                }
            }
            is SimpleReturn -> {
                if (hasReturn(graph.method)) {
                    appendGetField(graph, expr.field); nextLine()
                }
                ret()
            }
            is SimpleConstructorCall -> {
                appendConstructorCallImpl(graph, expr)
            }
            // todo test nullable variables
            is SimpleAllocateInstance -> {
                // this allocation is a ClassType, so it cannot be null ever
                val type = expr.allocatedType
                val struct = getStruct(Specialization(type), false)
                if (type.clazz == Types.Array.clazz) {
                    // todo test arrays of arrays...

                    // We must immediately allocate the array

                    val sizeParam = expr.paramsForLater[0]
                    check(sizeParam.type == Types.Int)

                    i32Const(inheritanceTable.getClassIndex(expr.specialization))
                    nextLine()

                    appendGetField(graph, sizeParam) // size for content

                    // content-field
                    val property = struct.properties[1]
                    builder.append("array.new_default $").append(property.wasmType.wasmName)
                    val wasmType = property.wasmType as WASMType.Ref
                    binary.arrayNewDefault(wasmType)
                    nextLine()

                    appendGetField(graph, sizeParam) // size field

                    check(struct.properties.size == 3) {
                        "Expected exactly three array properties (classIdx, content, size), got $struct"
                    }

                    builder.append("struct.new $").append(struct.typeName)
                    binary.structNew(struct.typeIndex)
                    nextLine()
                } else {

                    builder.append("struct.new_default $").append(struct.typeName)
                    binary.structNewDefault(struct.typeIndex)
                    nextLine()
                }
            }
            is SimpleMethodCall -> {
                // Number.toX() needs to be converted to a cast
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
                builder.append("local.get $").append(expr.field.name)
                binary.localGet(expr.field.id)
                nextLine()
            }
            is SimpleGetClassField -> {

                // load field owner
                appendGetField(graph, expr.self)

                val selfType = expr.self.type as ClassType
                if (selfType !in nativeNumbers) {
                    val struct = getStruct(Specialization(selfType), isNullable = false)
                    val fieldIndex = struct.getIndex(expr.field)
                    builder.append("struct.get $")
                        .append(struct.typeName).append(' ')
                        .append(fieldIndex)
                    binary.structGet(struct.typeIndex, fieldIndex)
                    nextLine()
                } else {
                    // field must be 'content'
                    check(expr.field.name == "content")
                }
            }
            is SimpleSetLocalField -> {
                val needsCopy = expr.value.type.needsCopy()
                appendGetField(graph, expr.value)
                if (needsCopy) appendCopy(graph, expr.value.type)

                builder.append("local.set $").append(expr.field.name)
                binary.localSet(expr.field.id)
                nextLine()
            }
            is SimpleSetClassField -> {
                val needsCopy = expr.value.type.needsCopy()

                appendGetField(graph, expr.self)
                appendGetField(graph, expr.value)
                if (needsCopy) appendCopy(graph, expr.value.type)

                val selfType = expr.self.type as ClassType
                val struct = getStruct(Specialization(selfType), isNullable = false)

                val fieldIndex = struct.getIndex(expr.field)
                builder.append("struct.set $")
                    .append(struct.typeName).append(' ')
                    .append(fieldIndex)
                binary.structSet(struct.typeIndex, fieldIndex)
                nextLine()
            }
            is SimpleCompare -> {
                val ownerType = expr.numberType
                appendGetField(graph, expr.left)
                appendGetField(graph, expr.right)
                appendNativeCompare(ownerType, expr.type)
            }
            is SimpleCheckEquals -> {
                val method = expr.method.resolved
                method.memberScope[ScopeInitType.AFTER_RESOLVE_TYPES]

                val ownerType = method.ownerScope.typeWithArgs2
                val paramType = method.valueParameters[0].type.specialize()
                if (ownerType == paramType && ownerType in nativeNumbers) {
                    appendGetField(graph, expr.left)
                    appendGetField(graph, expr.right)
                    appendNativeEquals(ownerType, expr.negated)
                } else {
                    TODO("Call method, then compare to zero for $ownerType,$paramType")
                }
            }
            is SimpleLocalFieldEqualsInt -> {
                builder.append("local.get $").append(expr.field.name)
                binary.localGet(expr.field.id)
                nextLine()

                i32Const(expr.expected)
                nextLine()

                builder.append("i32.eq")
                binary.u8(WASMOpcode.I32_EQ)
                nextLine()
            }
            is SimpleConditionalInt -> {
                appendGetField(graph, expr.condition)

                i32Const(expr.ifTrue - expr.ifFalse)
                nextLine()

                builder.append("i32.mul")
                binary.u8(WASMOpcode.I32_MUL)
                nextLine()

                i32Const(expr.ifFalse)
                nextLine()

                builder.append("i32.add")
                binary.u8(WASMOpcode.I32_ADD)
                nextLine()
            }
            is SimpleTailCall -> {
                // todo we have two options:
                //  if the target is further along, just jump there directly
                //  else, set the target id, and jump to the head

                val targetIndex = blockTableOptions.indexOf(expr.toBeCalled)
                if (targetIndex > currBlockTableIndex) {

                    val jumpDepth = blockTableDepth - targetIndex - 2 // 1 jumped by default, 1 for outer loop

                    builder.append("br \$bbt").append(targetIndex)
                        .append(" ;; ").append(jumpDepth)
                    binary.u8(WASMOpcode.BR)
                    binary.u8(jumpDepth) // how many extra loops/blocks are broken
                    nextLine()

                } else {
                    // jump to the head
                    val jumpDepth = blockTableDepth - 1 // 1 is jumped by default
                    i32Const(targetIndex); nextLine()
                    builder.append("local.set \$nextBlockId")
                    binary.localSet(nextBlockIdIdx)
                    nextLine()

                    builder.append("br \$blockTable ;; ").append(jumpDepth)
                    binary.u8(WASMOpcode.BR)
                    binary.u8(jumpDepth) // how many extra loops/blocks are broken
                    nextLine()
                }
            }
            is SimpleMerge -> {}
            is SimpleInstanceOf -> {
                instanceToClass(graph, expr.value)
                i32Const(inheritanceTable.getClassIndex(Specialization(expr.type))); nextLine()
                val method =
                    if (expr.type.clazz.isInterface()) inheritanceTable.instanceOfInterfaceCall
                    else inheritanceTable.instanceOfClassCall
                callMethod(method)
            }
            else -> throw NotImplementedError("Implement writing $expr (${expr.javaClass.simpleName})")
        }
    }

    fun instanceToClass(graph: SimpleGraph, self: SimpleField) {
        val selfType = self.type as ClassType
        if (selfType.clazz.isOpen()) {
            // load field owner
            appendGetField(graph, self)

            val struct = getStruct(Specialization(selfType), isNullable = false)
            val fieldIndex = 0
            builder.append("struct.get $")
                .append(struct.typeName).append(' ')
                .append(fieldIndex)
            binary.structGet(struct.typeIndex, fieldIndex)
            nextLine()
        } else {
            // value can be hardcoded
            i32Const(inheritanceTable.getClassIndex(selfType)); nextLine()
        }
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = resolveType(expr.thisInstance.type)
        if (isCast(methodName) && type in nativeNumbers) {
            appendCast(graph, expr, type)
            return true
        }

        if (methodName == "not" && type == Types.Boolean) {
            appendGetField(graph, expr.thisInstance)
            builder.append("i32.eqz")
            binary.u8(WASMOpcode.I32_EQZ)
            nextLine()
            return true
        }

        if (methodName == "inv" && type in nativeNumbers) {
            appendGetField(graph, expr.thisInstance)
            val isLong = type == Types.Long || type == Types.ULong
            if (isLong) i64Const(-1) else i32Const(-1)
            builder.append(if (isLong) " i64.xor" else " i32.xor")
            binary.u8(if (isLong) WASMOpcode.I64_XOR else WASMOpcode.I32_XOR)
            nextLine()

            when (type) {
                Types.UByte -> {
                    i32Const(0xff)
                    builder.append(" i32.and")
                    binary.u8(WASMOpcode.I32_AND)
                    nextLine()
                }
                Types.UShort -> {
                    i32Const(0xffff)
                    builder.append(" i32.and")
                    binary.u8(WASMOpcode.I32_AND)
                    nextLine()
                }
            }
            return true
        }

        return false
    }

    private fun appendCast(graph: SimpleGraph, expr: SimpleMethodCall, type: Type) {
        appendGetField(graph, expr.thisInstance)

        val outType0 = expr.dst.type
        if (outType0 == type) return  // easy

        val inType = getWASMType(type)
        val outType = getWASMType(outType0)

        val inFloat = type.isFloat()
        val outFloat = outType0.isFloat()
        when {
            inFloat && outFloat -> {
                if (inType == WASMType.F32) {
                    builder.append("f64.promote_f32")
                    binary.u8(WASMOpcode.F64_PROMOTE_F32)
                } else {
                    builder.append("f32.demote_f64")
                    binary.u8(WASMOpcode.F32_DEMOTE_F64)
                }
                nextLine()
            }
            inFloat -> {

                val minValue = getMinIntValue(outType0)
                val maxValue = getMaxIntValue(outType0)

                // todo we could also use i32.trunc_sat_f32_s to save on instructions & to gain speed
                //  https://github.com/WebAssembly/nontrapping-float-to-int-conversions/blob/main/proposals/nontrapping-float-to-int-conversion/Overview.md
                //  NaN = Int-Min -> idk...

                // clamp value to range:
                // this is done before casting, so we avoid
                // "float unrepresentable in integer range"
                if (inType == WASMType.F32) {

                    f32Const(minValue.toFloatCeil()); nextLine()
                    builder.append("f32.max")
                    binary.u8(WASMOpcode.F32_MAX)
                    nextLine()

                    f32Const(maxValue.toFloatFloor()); nextLine()
                    builder.append("f32.min")
                    binary.u8(WASMOpcode.F32_MIN)
                    nextLine()

                } else {

                    f64Const(minValue.toDoubleCeil()); nextLine()
                    builder.append("f64.max")
                    binary.u8(WASMOpcode.F64_MAX)
                    nextLine()

                    f64Const(maxValue.toDoubleFloor()); nextLine()
                    builder.append("f64.min")
                    binary.u8(WASMOpcode.F64_MIN)
                    nextLine()

                }

                val outTypeIsLong = outType == WASMType.I64
                val outTypeUnsigned = outType0.isUnsigned()
                val inTypeIsDouble = inType == WASMType.F64

                val id = outTypeUnsigned.toInt() +
                        outTypeIsLong.toInt(2) +
                        inTypeIsDouble.toInt(4)
                val (code, name) = inFloatInstr[id]
                builder.append(name)
                binary.u8(code)
                nextLine()

            }
            outFloat -> {

                val inTypeIsLong = inType == WASMType.I64
                val inTypeUnsigned = type.isUnsigned()
                val outTypeIsDouble = outType == WASMType.F64

                val id = inTypeUnsigned.toInt() +
                        inTypeIsLong.toInt(2) +
                        outTypeIsDouble.toInt(4)
                val (code, name) = outFloatInstr[id]
                builder.append(name)
                binary.u8(code)
                nextLine()
            }
            else -> {
                if (inType != outType) {
                    if (inType == WASMType.I32) {
                        check(outType == WASMType.I64)

                        val unsigned = outType0.isUnsigned()
                        builder.append(if (unsigned) "i64.extend_i32_u" else "i64.extend_i32_s")
                        binary.u8(if (unsigned) WASMOpcode.I64_EXTEND_I32U else WASMOpcode.I64_EXTEND_I32S)
                        nextLine()
                    } else {
                        check(inType == WASMType.I64)
                        check(outType == WASMType.I32)

                        builder.append("i32.wrap_i64")
                        binary.u8(WASMOpcode.I32_WRAP_I64)
                        nextLine()
                    }
                }

                maskToSmallInt(outType0)
            }
        }
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        if (expr.thisInstance.type !in nativeNumbers) return false
        val symbol = when (methodName) {
            "plus" -> "add"
            "minus" -> "sub"
            "times" -> "mul"
            "div" -> "div"
            "rem" -> "mod"
            "and", "or", "xor",
            "shl", "shr", "ushr" -> methodName
            "rotateLeft" -> "rotl"
            "rotateRight" -> "rotr"
            else -> return false
        }

        appendGetField(graph, expr.thisInstance)
        appendGetField(graph, expr.valueParameters[0])

        val type = expr.thisInstance.type
        val wasmType = getWASMType(type)
        if (wasmType == WASMType.I64 && when (symbol) {
                "shl", "shr", "ushr", "rotl", "rotr" -> true
                else -> false
            }
        ) {
            // todo Kotlin only reads the lowest bits, what does WASM do?
            builder.append("i64.extend_i32_u")
            binary.u8(WASMOpcode.I64_EXTEND_I32U)
            nextLine()
        }

        val numBits = type.getNumBits()
        if ((symbol == "rotl" || symbol == "rotr") && numBits <= 16) {

            builder.append("i32.").append(if (symbol == "rotl") "shl" else "shr_u")
            binary.u8(if (symbol == "rotl") WASMOpcode.I32_SHL else WASMOpcode.I32_SHR_U)
            nextLine()

            appendGetField(graph, expr.thisInstance)

            i32Const(numBits); nextLine()
            builder.append("i32.sub")
            binary.u8(WASMOpcode.I32_SUB)
            nextLine()

            appendGetField(graph, expr.valueParameters[0])

            builder.append("i32.").append(if (symbol == "rotl") "shr_u" else "shl")
            binary.u8(if (symbol == "rotl") WASMOpcode.I32_SHR_U else WASMOpcode.I32_SHL)
            nextLine()

            builder.append("i32.or")
            binary.u8(WASMOpcode.I32_OR)
            nextLine()

        } else {
            builder.append(wasmType.wasmName)
                .append('.').append(symbol)
            binary.u8(getSimpleMathOp(type, symbol))
            nextLine()
        }

        when (methodName) {
            "and", "or", "xor", "shr", "ushr" -> {} // no masking necessary
            else -> maskToSmallInt(type)
        }
        return true
    }

    fun maskToSmallInt(type: Type) {
        // clamp if small type
        when (type) {
            Types.Byte, Types.Short -> {
                val bits = if (type == Types.Byte) 24 else 16
                i32Const(bits); builder.append(' ')
                builder.append("i32.shl"); builder.append(' ')
                binary.u8(WASMOpcode.I32_SHL)

                i32Const(bits); builder.append(' ')
                builder.append("i32.shr_s"); nextLine()
                binary.u8(WASMOpcode.I32_SHR_S)
            }
            Types.UByte, Types.UShort -> {
                val mask = if (type == Types.Byte) 0xff else 0xffff
                i32Const(mask); builder.append(' ')
                builder.append("i32.and"); nextLine()
                binary.u8(WASMOpcode.I32_AND)
            }
            // else fine
        }
    }

    fun appendNativeCompare(valueType: Type, compareType: CompareType) {
        val numberType = getWASMType(valueType)
        val compareName = when (compareType) {
            CompareType.LESS -> "lt"
            CompareType.LESS_EQUALS -> "le"
            CompareType.GREATER -> "gt"
            CompareType.GREATER_EQUALS -> "ge"
        }
        builder.append(numberType.wasmName)
            .append('.').append(compareName)
        when {
            isNumberFloat(valueType) -> {}
            isNumberSigned(valueType) -> builder.append("_s")
            else -> builder.append("_u")
        }
        val wasmInstr = when (valueType) {
            Types.Byte, Types.Short, Types.Int -> when (compareType) {
                CompareType.LESS -> WASMOpcode.I32_LT_S
                CompareType.LESS_EQUALS -> WASMOpcode.I32_LE_S
                CompareType.GREATER -> WASMOpcode.I32_GT_S
                CompareType.GREATER_EQUALS -> WASMOpcode.I32_GE_S
            }
            Types.UByte, Types.UShort, Types.Char, Types.UInt -> when (compareType) {
                CompareType.LESS -> WASMOpcode.I32_LT_U
                CompareType.LESS_EQUALS -> WASMOpcode.I32_LE_U
                CompareType.GREATER -> WASMOpcode.I32_GT_U
                CompareType.GREATER_EQUALS -> WASMOpcode.I32_GE_U
            }
            Types.Long -> when (compareType) {
                CompareType.LESS -> WASMOpcode.I64_LT_S
                CompareType.LESS_EQUALS -> WASMOpcode.I64_LE_S
                CompareType.GREATER -> WASMOpcode.I64_GT_S
                CompareType.GREATER_EQUALS -> WASMOpcode.I64_GE_S
            }
            Types.ULong -> when (compareType) {
                CompareType.LESS -> WASMOpcode.I64_LT_U
                CompareType.LESS_EQUALS -> WASMOpcode.I64_LE_U
                CompareType.GREATER -> WASMOpcode.I64_GT_U
                CompareType.GREATER_EQUALS -> WASMOpcode.I64_GE_U
            }
            Types.Float, Types.Half -> when (compareType) {
                CompareType.LESS -> WASMOpcode.F32_LT
                CompareType.LESS_EQUALS -> WASMOpcode.F32_LE
                CompareType.GREATER -> WASMOpcode.F32_GT
                CompareType.GREATER_EQUALS -> WASMOpcode.F32_GE
            }
            Types.Double -> when (compareType) {
                CompareType.LESS -> WASMOpcode.F64_LT
                CompareType.LESS_EQUALS -> WASMOpcode.F64_LE
                CompareType.GREATER -> WASMOpcode.F64_GT
                CompareType.GREATER_EQUALS -> WASMOpcode.F64_GE
            }
            else -> throw NotImplementedError("Implement compare for $valueType")
        }
        binary.u8(wasmInstr)
        nextLine()
    }

    fun appendNativeEquals(valueType: Type, negated: Boolean) {
        val numberType = getWASMType(valueType)
        val compareName = if (negated) "ne" else "eq"
        builder.append(numberType.wasmName)
            .append('.').append(compareName)
        val wasmInstr = when (valueType) {
            Types.Byte, Types.Short, Types.Int,
            Types.UByte, Types.UShort, Types.Char, Types.UInt -> if (negated) WASMOpcode.I32_NE else WASMOpcode.I32_EQ
            Types.Long, Types.ULong -> if (negated) WASMOpcode.I64_NE else WASMOpcode.I64_EQ
            Types.Float, Types.Half -> if (negated) WASMOpcode.F32_NE else WASMOpcode.F32_EQ
            Types.Double -> if (negated) WASMOpcode.F64_NE else WASMOpcode.F64_EQ
            else -> throw NotImplementedError("Implement compare for $valueType")
        }
        binary.u8(wasmInstr)
        nextLine()
    }

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        val valueType = valueType as ClassType
        val method = valueType.clazz.methods0.first { it.name == "copy" && it.valueParameters.isEmpty() }
        val methodSpec = Specialization(method.scope, valueType.typeParameters ?: emptyParameterList())
        callMethod(methodSpec)
        nextLine()
    }

    fun getSimpleMathOp(type: Type, symbol: String): Int {
        return when (type) {
            Types.Byte, Types.Short, Types.Int,
            Types.UByte, Types.UShort, Types.UInt -> when (symbol) {
                "add" -> WASMOpcode.I32_ADD
                "sub" -> WASMOpcode.I32_SUB
                "mul" -> WASMOpcode.I32_MUL
                "div" -> if (type.isUnsigned()) WASMOpcode.I32_DIV_U else WASMOpcode.I32_DIV_S
                "mod" -> if (type.isUnsigned()) WASMOpcode.I32_REM_U else WASMOpcode.I32_REM_S

                "and" -> WASMOpcode.I32_AND
                "or" -> WASMOpcode.I32_OR
                "xor" -> WASMOpcode.I32_XOR

                "shl" -> WASMOpcode.I32_SHL
                "shr" -> if (type.isUnsigned()) WASMOpcode.I32_SHR_U else WASMOpcode.I32_SHR_S
                "ushr" -> WASMOpcode.I32_SHR_U
                "rotl" -> WASMOpcode.I32_ROTL
                "rotr" -> WASMOpcode.I32_ROTR
                else -> null
            }
            Types.Long, Types.ULong -> when (symbol) {
                "add" -> WASMOpcode.I64_ADD
                "sub" -> WASMOpcode.I64_SUB
                "mul" -> WASMOpcode.I64_MUL
                "div" -> if (type == Types.ULong) WASMOpcode.I64_DIV_U else WASMOpcode.I64_DIV_S
                "mod" -> if (type == Types.ULong) WASMOpcode.I64_REM_U else WASMOpcode.I64_REM_S

                "and" -> WASMOpcode.I64_AND
                "or" -> WASMOpcode.I64_OR
                "xor" -> WASMOpcode.I64_XOR

                // todo these expect a second type of i64, not i32
                "shl" -> WASMOpcode.I64_SHL
                "shr" -> if (type.isUnsigned()) WASMOpcode.I64_SHR_U else WASMOpcode.I64_SHR_S
                "ushr" -> WASMOpcode.I64_SHR_U
                "rotl" -> WASMOpcode.I64_ROTL
                "rotr" -> WASMOpcode.I64_ROTR
                else -> null
            }
            Types.Float, Types.Half -> when (symbol) {
                "add" -> WASMOpcode.F32_ADD
                "sub" -> WASMOpcode.F32_SUB
                "mul" -> WASMOpcode.F32_MUL
                "div" -> WASMOpcode.F32_DIV
                // mod does not exist
                else -> null
            }
            Types.Double -> when (symbol) {
                "add" -> WASMOpcode.F64_ADD
                "sub" -> WASMOpcode.F64_SUB
                "mul" -> WASMOpcode.F64_MUL
                "div" -> WASMOpcode.F64_DIV
                // mod does not exist
                else -> null
            }
            else -> null
        } ?: throw NotImplementedError("Missing opcode for $type.$symbol")
    }

    override fun appendValueParams(graph: SimpleGraph, valueParameters: List<SimpleField>, withBrackets: Boolean) {
        for (i in valueParameters.indices) {
            appendGetField(graph, valueParameters[i])
        }
    }

    override fun appendCallImpl(graph: SimpleGraph, expr: SimpleMethodCall) {
        if (expr.methods !is FullMap) {
            val options = inheritanceTable.createSwitchList(expr.specialization)
            if (options.size < 2) {
                val specialization = if (options.isNotEmpty()) options.first().second else expr.specialization
                appendGetField(graph, expr.thisInstance)
                appendValueParams(graph, expr.valueParameters)
                callMethod(specialization)
                nextLine()
                return
            } else if (options.size <= CSourceGenerator.INHERITANCE_SWITCH_LIMIT) {
                appendInheritedCallSwitch(graph, expr, options)
                return
            }

            // todo if thisInstance is strictly known (no child types),
            //  just call it directly

            appendGetField(graph, expr.thisInstance)
            appendValueParams(graph, expr.valueParameters)

            appendGetField(graph, expr.thisInstance)
            appendLoadClassIndex(expr.thisInstance)

            // resolve function to call
            if (expr.sample.ownerScope.isInterface()) {
                val callIndex = inheritanceTable.getInterfaceCallIndex(expr.specialization)
                i32Const(callIndex); nextLine()
                callMethod(inheritanceTable.interfaceCallSpec)
            } else {
                val callIndex = inheritanceTable.getClassCallIndex(expr.specialization)
                i32Const(callIndex); nextLine()
                callMethod(inheritanceTable.methodCallSpec)
            }

            // call it
            val typeIndex = getFunctionTypeIndex(expr.specialization)
            builder.append("call_indirect ")
                .append(typeList[typeIndex] as FunctionType)
            binary.callIndirect(typeIndex)
            nextLine()

        } else {
            if (hasThis(expr.sample)) appendGetField(graph, expr.thisInstance)
            if (hasSelf(expr.sample)) appendGetField(graph, expr.selfInstance!!)
            appendValueParams(graph, expr.valueParameters)
            // direct call
            callMethod(expr.specialization)
            nextLine()
        }
    }

    fun appendInheritedCallSwitch(
        graph: SimpleGraph, expr: SimpleMethodCall,
        options: List<Pair<Specialization, Specialization>>,
    ) {
        val returnType = getWASMType(expr.dst.type)
        for (optionIndex in options.indices) {

            val needsBranch = optionIndex < options.lastIndex
            if (needsBranch) {
                appendGetField(graph, expr.thisInstance)
                appendLoadClassIndex(expr.thisInstance)

                i32Const(inheritanceTable.getClassIndex(options[optionIndex].first)); nextLine()

                builder.append("i32.eq")
                binary.u8(WASMOpcode.I32_EQ)
                nextLine()

                beginIf(returnType)
            }

            appendGetField(graph, expr.thisInstance)
            appendValueParams(graph, expr.valueParameters)
            callMethod(options[optionIndex].second)
            nextLine()

            if (needsBranch) beginElse()
        }

        repeat(options.size - 1) {
            endIfElse() // close blocks
        }
    }

    fun appendLoadClassIndex(field: SimpleField) {
        val selfType = field.type as ClassType
        val struct = getStruct(Specialization(selfType), false)
        builder.append("struct.get $").append(struct.typeName).append(" 0")
        binary.structGet(struct.typeIndex, 0)
        nextLine()
    }

    fun appendConstructorCallImpl(graph: SimpleGraph, expr: SimpleConstructorCall) {
        appendGetField(graph, expr.thisInstance)
        appendValueParams(graph, expr.valueParameters)

        callMethod(expr.specialization)
        nextLine()
    }

    fun callMethod(method0: Specialization) {
        val method = method0.method
        val index = functionIndexMap[method0]
            ?: error("Missing $method @${method0}, cannot call it")
        builder.append("call $").append(getMethodName(method0))
        binary.call(index)
    }

}
