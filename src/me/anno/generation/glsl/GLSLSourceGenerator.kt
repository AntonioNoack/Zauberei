package me.anno.generation.glsl

import me.anno.generation.*
import me.anno.generation.c.CSourceGenerator
import me.anno.generation.llvm.LLVMProperty
import me.anno.generation.llvm.LLVMStructures
import me.anno.utils.IntArrayList
import me.anno.utils.NumberUtils.toInt
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.ResolutionUtils.getField
import me.anno.utils.StdlibLoader.loadText
import me.anno.utils.StringUtils.distance
import me.anno.utils.assertEquals
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleBoxCast
import me.anno.zauber.ast.simple.expression.SimpleConstructorCall
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleGetClassField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.ast.simple.fields.SimpleSetClassField
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.interpreting.Instance
import me.anno.zauber.interpreting.Runtime
import me.anno.zauber.interpreting.RuntimeCreate.createString
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.NullType
import java.io.File
import java.io.OutputStream

// todo generate compilable GLSL, including uniform buffers and bindings to get the necessary context data
// todo big difference to C: allocations are only possible on the stack, pointers are limited to out-variables
//  -> we have a global buffer/shared memory for dynamic allocations
//  same as with C, we don't have inheritance built-in, unlike C, we cannot use indirect calls
/**
 * this is like C, but we don't have proper pointers,
 * so reading from data must always happen via buffers
 *
 * todo we have some Vector- and Matrix-types predefined. It would be good to make use of them
 * */
class GLSLSourceGenerator : CSourceGenerator() {

    companion object {

        private val LOGGER = LogManager.getLogger(GLSLSourceGenerator::class)

        val protectedGlslTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "bool"),

                    Byte to BoxedType("Byte", "int"),
                    Short to BoxedType("Short", "int"),
                    Int to BoxedType("Int", "int"),
                    // todo longs need to be implemented using hi+lo

                    UByte to BoxedType("Byte", "uint"),
                    UShort to BoxedType("Short", "uint"),
                    UInt to BoxedType("Int", "uint"),

                    Char to BoxedType("Char", "uint"),
                    // todo half and double are not necessarily supported, and may have to be replaced with float
                    Half to BoxedType("Half", "float"), // unsupported in my shader :/
                    Float to BoxedType("Float", "float"),
                    Double to BoxedType("Double", "double"),
                )
            }
        }

        val nativeGlslTypes by threadLocal { protectedGlslTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeGlslNumbers by threadLocal { nativeGlslTypes - Types.Boolean }

        // todo why is 'buffer' still allowed as a field name?
        private val glslKeywords = "int,float,buffer,texture,this".split(',').toSet()

        const val STRING_BUILDER = 14
        const val NEXT_GC = 15
        const val TOTAL_RESERVED = 16
    }

    override val protectedTypes: Map<ClassType, BoxedType> get() = protectedGlslTypes
    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativeGlslNumbers
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativeGlslTypes

    override val keywords: Set<String> get() = glslKeywords

    // todo all static-blocks should be executed at compile-time, I think:
    //  because CPU execution is faster than GPU execution
    // todo all references to instances must be replaced with memory indices...
    // todo same for static-blocks

    init {
        // we want nice code
        onlyCheapSimplifications = false
        generateNiceBlocks = true
        constructorName = "XinitX"
        thisParamName = "this_" // 'this' is reserved
    }

    // todo don't append/dependency-find object-constructors, because they are compile-time only anyway

    // todo all strings and such will be entered into this buffer
    val memory = IntArrayList(64)

    init {
        // ensure not empty & we want no data to have address 0
        writeNullsUntil(TOTAL_RESERVED)
    }

    val memoryName = "_memory"

    private fun writeNullsUntil(length: Int) {
        while (length > memory.size) {
            memory.add(0)
        }
    }

    fun OutputStream.writeI32(value: Int) {
        // little endian
        write(value)
        write(value shr 8)
        write(value shr 16)
        write(value shr 24)
    }

    fun writeValueAt(index: Int, value: Int) {
        check(memory.size > index)
        memory[index] = value
    }

    val instanceAddress = HashMap<Instance, Int>()
    val instanceList = ArrayList<Instance>()

    val structures = LLVMStructures(this, 4)
    val arrayHeaderSizeInBytes = 8 // 4 for classIndex, 4 for size

    private fun getSizeInBytes(instance: Instance): Int {
        val spec = Specialization(instance.clazz.type as ClassType)
        if (spec.clazz == Types.Array.clazz) {
            val elementType = spec.typeParameters[0]
            val elementSize = getElementSizeInBytes(elementType)
            val sizeField = instance.clazz.fields.indexOfFirst { it.name == "size" }
            val arraySize = instance.fields[sizeField]!!.castToInt()
            return arrayHeaderSizeInBytes + arraySize * elementSize
        } else {
            val struct = structures.getStruct(spec)
            return struct.sizeInBytes
        }
    }

    fun getElementSizeInBytes(elementType: Type): Int {
        return if (elementType in nativeTypes) {
            (elementType.getNumBits() + 7) shr 3
        } else structures.ptrSizeInBytes
    }

    fun getInstanceAddress(instance: Instance): Int {
        if (instance.clazz.type == NullType) return 0

        return instanceAddress.getOrPut(instance) {
            instanceList.add(instance)
            val address = memory.size
            val sizeInBytes = getSizeInBytes(instance)
            val sizeInElements = (sizeInBytes + 3).shr(2)
            writeNullsUntil(address + sizeInElements)
            address
        }
    }

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        inheritanceTable = InheritanceTable(data)
        generateCodeImpl1(dst, data, mainMethod)
        inheritanceTable.generateFiles(dst)

        // todo inheritance tables belong into memory, too...

        ensureAllPropertiesAreInMemory()

        writeValueAt(STRING_BUILDER, findStringBuilderAddress())
        writeValueAt(NEXT_GC, memory.size)

        val folder = File(dst.parentFile, "data"); folder.mkdirs()
        File(folder, "memory.bin")
            .outputStream().buffered().use { bos ->
                for (i in 0 until memory.size) {
                    bos.writeI32(memory[i])
                }
            }
    }

    private fun ensureAllPropertiesAreInMemory() {
        var i = 0
        while (i < instanceList.size) {
            val instance = instanceList[i++]
            val address = instanceAddress[instance]!!
            // println("_memory[$address]: $instance")

            val classSpec = Specialization(instance.clazz.type as ClassType)
            val structure = structures.getStruct(classSpec)
            val fields = instance.clazz.fields
            for (property in structure.properties) {
                val field = property.field
                if (property.index == 0) {
                    check(field == null)
                    check(property.offsetInBytes == 0)
                    memory[address] = inheritanceTable.getClassIndex(classSpec)
                } else if (field == null) {
                    if (classSpec.clazz == Types.Array.clazz) {

                        // write array contents

                        val elementType = classSpec.typeParameters[0]
                        val dst = address + arrayHeaderSizeInBytes.shr(2)
                        when (elementType) {
                            Types.Boolean -> {
                                val content = instance.rawValue as BooleanArray
                                for (i in content.indices) {
                                    writeByteAt(dst, i, content[i].toInt().toByte())
                                }
                            }
                            Types.Byte, Types.UByte -> {
                                val content = instance.rawValue as ByteArray
                                for (i in content.indices) {
                                    writeByteAt(dst, i, content[i])
                                }
                            }
                            Types.Short, Types.UShort -> {
                                val content = instance.rawValue as ShortArray
                                for (i in content.indices) {
                                    writeShortAt(dst, i, content[i])
                                }
                            }
                            Types.Half -> {
                                val content = instance.rawValue as ShortArray
                                for (i in content.indices) {
                                    writeShortAt(dst, i, content[i])
                                }
                            }
                            Types.Int, Types.UInt -> {
                                val content = instance.rawValue as IntArray
                                for (i in content.indices) {
                                    memory[dst + i] = content[i]
                                }
                            }
                            Types.Float -> {
                                val content = instance.rawValue as FloatArray
                                for (i in content.indices) {
                                    memory[dst + i] = content[i].toRawBits()
                                }
                            }
                            else -> {
                                check(elementType !in nativeTypes) { "Implement writing $elementType into array" }
                                if (elementType.isValue()) {
                                    TODO("Write array of values $elementType into buffer")
                                } else {
                                    TODO("Write array of references $elementType into buffer")
                                }
                            }
                        }

                    } else {
                        error { "Missing field for $property in $structure" }
                    }
                } else {
                    val type = field.resolveValueType(classSpec)
                    if (field.name == "content" &&
                        instance.clazz.type in ASTSimplifier.nativeNumbers
                    ) {
                        TODO("Write content for $instance")
                    } else {

                        val fieldIndex = fields.indexOf(field)
                        check(fieldIndex >= 0) {
                            "Missing $field in $instance"
                        }

                        val offset = property.offsetInBytes
                        val dst = address + offset.shr(2)
                        val value = instance.fields[fieldIndex]
                            ?: error("$instance is partially uninitialized")

                        when (type) {

                            Types.Boolean -> writeByteAt(dst, offset.and(3), value.castToBool().toInt().toByte())
                            Types.Byte -> writeByteAt(dst, offset.and(3), value.castToByte())
                            Types.UByte -> writeByteAt(dst, offset.and(3), value.castToUByte().toByte())

                            Types.Short -> writeShortAt(dst, offset.shr(1).and(1), value.castToShort())
                            Types.UShort -> writeShortAt(dst, offset.shr(1).and(1), value.castToUShort().toShort())
                            Types.Half -> writeShortAt(dst, offset.shr(1).and(1), value.castToHalf().binary)

                            Types.Int -> memory[dst] = value.castToInt()
                            Types.UInt -> memory[dst] = value.castToUInt().toInt()
                            Types.Float -> memory[dst] = value.castToFloat().toRawBits()

                            else -> {
                                check(type !in nativeNumbers) {
                                    "Should write $type natively"
                                }
                                // a reference
                                check(property.offsetInBytes.and(3) == 0)
                                check(property.llvmType.sizeInBytes == 4) {
                                    "Expected $type to have size of 4 bytes, got ${property.llvmType}"
                                }
                                memory[dst] = getInstanceAddress(value)
                            }
                        }
                    }
                }
            }
        }
    }

    fun writeByteAt(intOffset: Int, subIndex: Int, value: Byte) {
        val cellIndex = intOffset + subIndex.shr(2)
        val shift = subIndex.and(3) * 8
        writeMaskedAt(cellIndex, value.toInt(), 0xff, shift)
    }

    fun writeShortAt(intOffset: Int, subIndex: Int, value: Short) {
        val cellIndex = intOffset + subIndex.shr(1)
        val shift = subIndex.and(1) * 16
        writeMaskedAt(cellIndex, value.toInt(), 0xffff, shift)
    }

    fun writeMaskedAt(cellIndex: Int, value: Int, mask: Int, shift: Int) {
        val shiftedMask = mask.shl(shift)
        memory[cellIndex] = memory[cellIndex].and(shiftedMask.inv()) or
                value.shl(shift).and(shiftedMask)
    }

    private fun findStringBuilderAddress(): Int {
        val owner = Runtime.runtime.getObjectInstance(langScope)
        val field = langScope.getField("printed")
        val instance = owner[field]
        return getInstanceAddress(instance)
    }

    private fun generateCodeImpl1(dst: File, data: DependencyData, mainMethod: Method) {
        val writer = FileWithImportsWriter(this, dst)
        try {

            defineNullableAnnotation(dst, writer)
            defineMainMethodCall(dst, writer, mainMethod)

            generateCodeImpl(dst, data, writer)

        } finally {
            writer.joinIntoOneGLSLFile(data, dst)
        }
    }

    private fun FileWithImportsWriter.joinIntoOneGLSLFile(data: DependencyData, dst: File) {
        val dstFile = File(dst, "ComputeShader.glsl")

        val builder = StringBuilder()
        builder.append(loadText("files/GLSLStdlib.glsl"))
        val l0 = builder.indexOf('\n') + 1 // version must be on first line, extensions follow

        val imports = HashSet<String>()
        val written = HashSet<FileEntry>()

        lateinit var handleImports: (FileEntry) -> Unit

        fun appendFile(file: File) {
            val content = newContent[file]
            if (content != null) {
                if (written.add(content)) {
                    handleImports(content)

                    builder
                        .append("// ").append(file).append('\n')
                        .append(content.content).trimEnd()
                        .append("\n\n")
                }
            } else {
                // todo add exception for main.h: that's fine to be missing, it only contains main() anyway
                val fileName = file.toString()
                LOGGER.warn(
                    "Missing file $fileName, most similar: " +
                            "${newContent.keys.minBy { fileName.distance(it.toString()) }}"
                )
            }
        }

        handleImports = { content ->
            for ((import, import2) in content.imports) {
                if (imports.add(import)) {
                    val fileName = toStringLC(import2.path) + ".h"
                    appendFile(File(dst, fileName))
                }
            }
        }

        for ((srcFile, content) in newContent.entries) {
            if (content != null && srcFile.extension == "c") {
                val headerFile = File(srcFile.parentFile, srcFile.nameWithoutExtension + ".h")
                appendFile(headerFile)
                appendFile(srcFile)
            }
        }

        if (false && Specialization(Types.Half) in data.createdClasses) {
            // unsupported on my gpu :(
            builder.insert(l0, "#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require\n")
        }

        dstFile.writeText(builder.toString())
    }

    private fun toStringLC(path: List<String>): String {
        val l0 = builder.length
        appendPathLc(path, lastUpper = true, "/")
        val str = builder.substring(l0, builder.length)
        builder.setLength(l0)
        return str
    }

    private fun StringBuilder.trimEnd(): StringBuilder {
        while (isNotEmpty() && last().isWhitespace()) setLength(length - 1)
        return this
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        val instance = Runtime.runtime.getObjectInstance(objectScope)
        val instanceSlot = getInstanceAddress(instance)
        builder.append(instanceSlot)
    }

    override fun appendObjectGetter(
        classScope: Scope, className: String,
        packagePrefix: String, headerOnly: Boolean
    ) {
        // we have to compile-time compute all objects, because it would also cause congestion;
        //  -> now we just have an integer left, and that is so simple,
        //     that we can just replace it everywhere with the hardcoded address
    }

    // we should cache all strings
    private val stringValues = HashMap<String, Instance>()
    override fun appendString(expr: String) {
        val instance = stringValues.getOrPut(expr) {
            Runtime.runtime.createString(expr)
        }
        val addr = getInstanceAddress(instance)
        builder.append(addr)
    }

    override fun appendStringImpl(value: String, scope: Scope) {
        appendString(value)
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (type) {
            Types.Char -> {
                builder.append(expr.asInt.toUShort())
            }
            Types.Long -> {
                builder.append("{")
                    .append(expr.asInt.shr(32)).append(',')
                    .append(expr.asInt.toInt()).append('}')
            }
            Types.ULong -> {
                builder.append("{")
                    .append(expr.asInt.ushr(32)).append("u,")
                    .append(expr.asInt.toUInt()).append("u}")
            }
            else -> super.appendNumber(type, expr)
        }
    }

    override fun declareStruct(
        classScope: Scope, className: String,
        packagePrefix: String, fields: Collection<Specialization>
    ) {
        if (!classScope.typeWithArgs2.isValue() || classScope.typeWithArgs2 in nativeTypes) return

        appendClassFlags(classScope)
        builder.append("struct ")
        builder.append(packagePrefix)
        builder.append(className)
        writeBlock {

            // todo long and ulong should be marked as value types...
            when (classScope) {
                Types.Long.clazz -> {
                    builder.append("int hi;"); nextLine()
                    builder.append("int lo;"); nextLine()
                }
                Types.ULong.clazz -> {
                    builder.append("uint hi;"); nextLine()
                    builder.append("uint lo;"); nextLine()
                }
                else -> {
                    // append fields
                    if (!classScope.isValueType()) {
                        builder.append("uint ").append(CLASS_INDEX_NAME).append(';')
                        nextLine()
                    }

                    declareClassFields(classScope, fields, true, headerOnly = true)
                }
            }
        }
        removeTrailingWhitespace()
        builder.append(';')
        nextLine()
    }

    override fun appendType(type: Type, scope: Scope, needsBoxedType: Boolean, withSuffix: Boolean) {
        val type = resolveType(type)
        if (!needsBoxedType) {
            val protected = protectedTypes[type]
            if (protected != null) {
                builder.append(protected.native)
                // no suffix necessary
                return
            }
        }

        var printedType = type
        while (printedType is GenericType) {
            printedType = printedType.superBounds
        }

        val isReferenceType = needsBoxedType || !type.isValue()
        if (isReferenceType && withSuffix) {
            builder.append("uint ")
            comment {
                builder.append("ptr<")
                appendTypeImpl(printedType, scope, true)
                builder.append(">")
            }
        } else {
            appendTypeImpl(printedType, scope, needsBoxedType)
        }
    }

    override fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        cppFiles += getMainMethodFile(dst)
        val methodName = getMethodName(Specialization.fromSimple(mainMethod.memberScope))
        check(!hasThis(mainMethod)) { "Main method must not have this-parameter" }

        // todo why is this not properly imported?!?
        ensureImport(mainMethod.ownerScope)

        builder.append("void main() ")
        writeBlock {
            // todo convert argc/argv to String-array, if needed
            builder.append(methodName).append("();"); nextLine()
        }

        return FileEntry(emptyList(), this)
    }

    override fun appendNumberContentInitialization(constructor: Constructor) {
        // todo in many cases, we need to cast
        // todo for small number types, we must also put the remainder of the offset into the load/store function as a shift-variable
        val offset = findProperty(constructor.ownerScope, "content").offsetInBytes
        assertEquals(0, offset.and(3))
        builder.append(memoryName).append("[").append(thisParamName).append(" + ")
            .append(offset shr 2)
            .append("] = ")
        val type = constructor.ownerScope.typeWithArgs2
        appendStorePrefix(type)
        builder.append("content")
        appendStoreSuffix(type)
        builder.append(';')
        nextLine()
    }

    fun findProperty(scope: Scope, fieldName: String): LLVMProperty {
        val struct = structures.getStruct(Specializations.specialization.withScope(scope))
        return struct.properties.first { it.field?.name == fieldName }
    }

    fun findProperty(field: Field, fieldSpec: Specialization): LLVMProperty {
        val ownerSpec = fieldSpec.withScope(field.ownerScope)
        val struct = structures.getStruct(ownerSpec)
        return struct.properties.first { it.field == field }
    }

    fun appendLoadPrefix(type: Type) {
        when (type) {
            Types.Float -> builder.append("uintBitsToFloat(")
            Types.Byte, Types.Short, Types.Int -> builder.append("int(")
        }
    }

    fun appendLoadSuffix(type: Type) {
        when (type) {
            Types.Float,
            Types.Byte, Types.Short, Types.Int -> builder.append(")")
        }
    }

    fun appendStorePrefix(type: Type) {
        when (type) {
            Types.Float -> builder.append("floatBitsToUint(")
            Types.Byte, Types.Short, Types.Int -> builder.append("uint(")
        }
    }

    fun appendStoreSuffix(type: Type) {
        when (type) {
            Types.Float,
            Types.Byte, Types.Short, Types.Int -> builder.append(")")
        }
    }

    override fun declareThis(method: MethodLike, scope: Scope) {
        if (hasThis(method)) {
            // when we have inout, we don't need the boxed type :)
            if (method.ownerScope.isValueType()) {
                builder.append("inout ")
            }
            appendType(scope.typeWithArgs.specialize(), scope, false, withSuffix = true)
            builder.append(' ').append(thisParamName)
        }
    }

    override fun appendOwnerCastPrefix(ownerType: Type, scope: Scope) {
        appendType(ownerType, scope, true, withSuffix = true)
        builder.append('(')
    }

    override fun appendOwnerCastSuffix(ownerType: Type, scope: Scope) {
        builder.append(')')
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val thisType = expr.thisInstance.type
        val castTargetType = getCastTargetType(methodName)
        if (castTargetType != null && thisType in nativeNumbers) {
            // todo some types need extra clamping/masking
            appendAssign(graph, expr)
            builder.append(nativeNumbers[castTargetType]!!.native).append('(')
            appendFieldName(graph, expr.thisInstance)
            builder.append(')')
            appendClampingOrMasking(expr.dst.type)
            return true
        } else return super.appendUnaryOperator(graph, expr, methodName)
    }

    fun appendClampingOrMasking(type: Type) {
        when (type) {
            Types.Byte -> builder.append(" & 0xff")
            Types.UByte -> builder.append(" & 0xffu")
            Types.Short -> builder.append(" & 0xffff")
            Types.UShort, Types.Char -> builder.append(" & 0xffffu")
        }
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        // comment { builder.append(expr.javaClass.simpleName) }
        when (expr) {
            is SimpleAllocateInstance -> {
                if (expr.allocatedType.clazz == Types.Array.clazz) {
                    check(expr.paramsForLater.size == 1)

                    val elementType = expr.allocatedType.typeParameters!![0]
                    val elementSizeInBytes = getElementSizeInBytes(elementType)

                    builder.append("_gcNew((").append(arrayHeaderSizeInBytes).append(" + ")
                    appendFieldName(graph, expr.paramsForLater[0]) // size
                    builder
                        .append(" * ").append(elementSizeInBytes)
                        .append(" + 3) >> 2, ").append(inheritanceTable.getClassIndex(expr.specialization))
                        .append(')')

                } else if (!expr.allocatedType.isValue()) {
                    // call GC-aware alloc instead
                    val structure = structures.getStruct(expr.specialization)

                    builder.append("_gcNew(").append((structure.sizeInBytes + 3) shr 2)
                        .append(", ").append(inheritanceTable.getClassIndex(expr.specialization))
                        .append(')')
                } else {
                    appendDefaultValue(expr.allocatedType)
                }
            }
            is SimpleConstructorCall -> {
                // todo 'this' in value-constructor must be marked as inout
                val methodName = getMethodName(expr.specialization)
                builder.append(methodName).append('(')
                appendFieldName(graph, expr.thisInstance, "")
                appendValueParams(graph, expr.valueParameters, withBrackets = false)
                builder.append(");")
            }
            is SimpleGetClassField -> {
                if (expr.dst.dst.id >= 0) {
                    if (expr.self.type.isValue()) {
                        appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                        builder.append(".")
                        appendFieldName(expr.field)
                    } else {

                        val fieldType = expr.dst.type
                        val property = findProperty(expr.field, expr.specialization)
                        val offset = property.offsetInBytes

                        appendLoadPrefix(fieldType)
                        if (property.llvmType.sizeInBytes < 4) builder.append('(')
                        builder.append(memoryName).append("[")
                        appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                        builder.append(" + ").append(offset shr 2)
                        builder.append(']')

                        if (property.llvmType.sizeInBytes < 4) {
                            if (offset.and(3) != 0) {
                                builder.append(" >> ").append(offset.and(3) * 8)
                                builder.append(") & ").append(property.llvmType.bitMask).append('u')
                            }
                        } else check(offset.and(3) == 0)
                        appendLoadSuffix(fieldType)

                        comment { builder.append(expr.field.name) }
                    }
                } // else skip
            }
            is SimpleSetClassField -> {
                if (expr.self.type.isValue()) {
                    appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                    builder.append(".")
                    appendFieldName(expr.field)
                    builder.append(" = ")
                    appendFieldName(graph, expr.value)
                } else {

                    val fieldType = expr.value.type
                    val property = findProperty(expr.field, expr.specialization)
                    val offset = property.offsetInBytes

                    builder.append(memoryName).append("[")
                    appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                    builder.append(" + ").append(offset shr 2)
                    builder.append("]")
                    builder.append(" = ")

                    if (property.llvmType.sizeInBytes < 4) {
                        builder.append('(')
                        builder.append(memoryName).append("[")
                        appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                        builder.append(" + ").append(offset shr 2)
                        // this bitmask depends on the offset
                        val invMask = (property.llvmType.bitMask shl (offset.and(3) * 8)).inv()
                        builder.append("] & ").append(invMask.toUInt())
                            .append("u) | (")
                    } else check(offset.and(3) == 0)

                    appendStorePrefix(fieldType)
                    appendFieldName(graph, expr.value)
                    appendStoreSuffix(fieldType)

                    if (property.llvmType.sizeInBytes < 4) {
                        builder.append(" & ").append(property.llvmType.bitMask).append("u)")
                        if (offset.and(3) != 0) {
                            builder.append(" << ").append(offset * 8)
                        }
                    }

                    comment { builder.append(expr.field.name) }
                }
            }
            is SimpleBoxCast -> {

                val src = expr.src
                val dst = expr.dst

                val srcType = src.type
                val dstType = dst.type

                val srcNum = srcType in nativeNumbers
                val dstNum = dstType in nativeNumbers

                val srcValue = srcType.isValue()
                val dstValue = dstType.isValue()

                val srcRef = !srcNum && !srcValue
                val dstRef = !dstNum && !dstValue

                when {
                    dstValue && srcValue -> error("Cannot convert $src to $dst implicitly")
                    srcValue -> {

                        val spec = Specialization(src.type as ClassType)
                        val structure = structures.getStruct(spec)

                        builder.append("_gcNew(").append((structure.sizeInBytes + 3) shr 2)
                        builder.append(", ")
                            .append(inheritanceTable.getClassIndex(spec))
                            .append(");"); nextLine()

                        if (src.type in nativeNumbers) {
                            builder.append("((")
                            appendType(src.type, expr.scope, true, withSuffix = true)
                            builder.append(") ")
                            appendFieldName(graph, dst)
                            builder.append(")->content = ")
                            appendFieldName(graph, src)
                        } else {
                            val fields = src.type.clazz.fields
                            for (field in fields) {
                                builder.append("((")
                                appendType(src.type, expr.scope, true, withSuffix = true)
                                builder.append(") ")
                                appendFieldName(graph, dst)
                                builder.append(")->").append(field.newName).append(" = ")
                                appendFieldName(graph, src)
                                builder.append(".").append(field.newName).append(';')
                                nextLine()
                            }
                        }
                    }
                    srcRef && dstNum -> {
                        // unboxing
                        TODO("unboxing")
                        builder.append("((")
                        appendType(dst.type, expr.scope, true, withSuffix = true)
                        builder.append(") ")
                        appendFieldName(graph, src)
                        builder.append(")->content")
                    }
                    dstValue -> error("Unboxing $src to $dst")
                    else -> {
                        appendType(expr.dst.type, expr.scope, true, withSuffix = true)
                        builder.append('(')
                        appendFieldName(graph, expr.src)
                        builder.append(")")
                    }
                }
            }
            is SimpleTailCall -> {
                builder.append("nextBlockId = ").append(expr.toBeCalled.id).append(';')
                nextLine()
                // todo we somehow need to make sure we just far enough, but GLSL has neither labels nor jumps :/
                // builder.append("continue blockTable;")
                builder.append("continue;")
            }
            else -> super.appendInstrImpl(graph, expr)
        }
    }

    override fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("int nextBlockId = 0;"); nextLine()
        // todo we somehow need to make sure we just far enough, but GLSL has neither labels nor jumps :/
        // builder.append("blockTable: while (true) ")
        builder.append("while (true) ")
        writeBlock {
            builder.append("switch (nextBlockId) ")
            writeBlock {
                val targets = findTailCallTargets(graph)
                val blocks = graph.blocks
                for (i in blocks.indices) {
                    val block = blocks[i]
                    if (i == 0 || targets[block.id]) {
                        builder.append("case ").append(block.id).append(':')
                        writeBlock {
                            appendBlock(graph, block)
                        }
                    }
                }
            }
        }
    }

    override fun appendSelfForFieldAccess(graph: SimpleGraph, self: SimpleField, field: Field, exprScope: Scope) {
        if (self.type is ClassType && self.type.clazz.isObjectLike()) {
            appendObjectInstance(field, exprScope, "")
        } else {
            val fieldSelfType = field.selfType
            val needsCast = self.type != fieldSelfType
            if (needsCast && fieldSelfType != null) {
                check(false) { "I think this should be handled in boxcast" }
                appendType(fieldSelfType, exprScope, true)
                builder.append('(')
                appendFieldName(graph, self, "")
                builder.append(')')
            } else {
                appendFieldName(graph, self, "")
            }
        }
    }

    override fun markValueAsReference() {
        // nothing to do here
    }

    override fun appendFieldName(graph: SimpleGraph, field: SimpleField, forFieldAccess: String) {
        assertEquals("", forFieldAccess)
        if (field.isOwnerThis(graph)) {
            if (meansContent(field, forFieldAccess)) {
                val property = findProperty((field.type as ClassType).clazz, "content")
                val fieldOffset = property.offsetInBytes
                assertEquals(0, fieldOffset.and(3))
                appendLoadPrefix(field.type) // this.content only appears in a getter (and constructor), so load is fine here
                builder.append(memoryName)
                    .append('[').append(thisParamName).append(" + ")
                    .append(fieldOffset shr 2).append(']')
                appendLoadSuffix(field.type)
            } else {
                /*builder.append(memoryName)
                    .append('[').append(thisParamName).append(" + ")
                    .append(fieldOffset shr 2).append(']')*/
                builder.append(thisParamName)// .append("/* owner-this, not content */")
            }
        } else super.appendFieldName(graph, field, forFieldAccess)
    }

    override fun appendSpecialValue(type: SpecialValue) {
        when (type) {
            SpecialValue.TRUE -> builder.append("true")
            SpecialValue.FALSE -> builder.append("false")
            SpecialValue.NULL -> builder.append("0u /* null */")
        }
    }

    override fun appendFirstParameter(graph: SimpleGraph, type: Type, expr: SimpleMethodCall) {
        if (type != Types.String && expr.thisInstance.isOwnerThis(graph)) {
            check(type is ClassType && type.clazz.fields.any { it.name == "content" }) {
                "$type is missing field 'content'"
            }
            /* appendLoadPrefix(type)
             builder.append(memoryName).append('[')*/
            appendFieldName(graph, expr.thisInstance, "")
            /*val offset = findProperty(type.clazz, "content").offsetInBytes
            builder.append(" + ").append(offset shr 2)
            builder.append(']')
            appendLoadSuffix(type)*/
        } else {
            appendFieldName(graph, expr.thisInstance)
        }
    }

    override fun appendArrayContentField(classScope: Scope, headerOnly: Boolean) {
        // is implicit
        comment { builder.append("... content") }; nextLine()
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        // done when allocating instance
    }

    override fun appendArrayGetter(method0: Specialization) {
        writeBlock {
            val elementType = method0.typeParameters[0]
            val elementLLVMType = structures.getInnerType(elementType)
            val elementSizeInBytes = elementLLVMType.sizeInBytes

            val offset = 8
            check(offset.and(3) == 0)

            builder.append("return ")
            appendLoadPrefix(elementType)
            if (elementSizeInBytes in 1..2) builder.append('(')
            builder.append(memoryName).append('[').append(thisParamName)
            builder.append(" + ").append(offset shr 2).append('u')
            when (elementSizeInBytes) {
                1 -> builder.append(" + uint(index >> 2)")
                2 -> builder.append(" + uint(index >> 1)")
                else -> builder.append(" + uint(index)")
            }
            builder.append(']')

            when (elementSizeInBytes) {
                1 -> {
                    builder.append(" >> ((index & 3) * 8)")
                    builder.append(") & ").append(elementLLVMType.bitMask).append('u')
                }
                2 -> {
                    builder.append(" >> ((index & 1) * 16)")
                    builder.append(") & ").append(elementLLVMType.bitMask).append('u')
                }
            }
            appendLoadSuffix(elementType)
            builder.append(';'); nextLine()
        }
    }

    override fun appendArraySetter(method0: Specialization) {
        writeBlock {
            val elementType = method0.typeParameters[0]
            val elementLLVMType = structures.getInnerType(elementType)
            val elementSizeInBytes = elementLLVMType.sizeInBytes

            val offset = 8
            check(offset.and(3) == 0)

            builder.append(memoryName)
                .append('[').append(thisParamName)
                .append(" + ").append(arrayHeaderSizeInBytes shr 2).append('u')

            // todo saving structs & longs/doubles isn't as easy, there we need to write multiple fields
            when (elementSizeInBytes) {
                1 -> builder.append(" + uint(index >> 2)")
                2 -> builder.append(" + uint(index >> 1)")
                else -> builder.append(" + uint(index)")
            }

            builder.append("] = ")

            if (elementSizeInBytes in 1..2) {
                builder.append('(')
                builder.append(memoryName)
                    .append("[").append(thisParamName)
                    .append(" + ").append(arrayHeaderSizeInBytes shr 2)
                    .append("u + ")
                when (elementSizeInBytes) {
                    1 -> builder.append("uint(index >> 2)")
                    2 -> builder.append("uint(index >> 1)")
                }
                // this bitmask depends on the index
                builder.append("] & ~(").append(elementLLVMType.bitMask)
                    .append("u << ")
                when (elementSizeInBytes) {
                    1 -> builder.append("((index & 3) * 8)")
                    2 -> builder.append("((index & 1) * 16)")
                }
                builder.append(")) | ((")
            }

            appendStorePrefix(elementType)
            builder.append("value")
            appendStoreSuffix(elementType)

            if (elementSizeInBytes in 1..2) {
                builder.append(" & ").append(elementLLVMType.bitMask).append("u)")
                when (elementSizeInBytes) {
                    1 -> builder.append(" << ((index & 3) * 8)")
                    2 -> builder.append(" << ((index & 1) * 16)")
                }
                builder.append(')')
            }

            builder.append(';'); nextLine()
        }
    }

    override fun appendDefaultValue(valueType: Type) {
        when (valueType) {
            Types.Boolean -> builder.append("false")
            Types.Half, Types.Float, Types.Double -> builder.append("0.0")
            Types.UByte, Types.UShort, Types.Char, Types.UInt -> builder.append("0u")
            Types.Byte, Types.Short, Types.Int -> builder.append("0")
            // todo it looks like we need helpers... we cannot just instantiate them like that(?)
            Types.Long -> builder.append("{0,0}")
            Types.ULong -> builder.append("{0u,0u}")
            else -> {
                if (valueType.isValue()) {
                    val spec = Specialization(valueType as ClassType)
                    val structure = structures.getStruct(spec)
                    builder.append('{')
                    for (i in 1 until structure.properties.size) { // classIndex is skipped
                        if (i > 1) builder.append(',')
                        val type = structure.properties[i].field?.valueType ?: Types.Any
                        appendDefaultValue(type)
                    }
                    builder.append('}')
                } else builder.append("0u")
            }
        }
    }

    override fun appendClassIndex(graph: SimpleGraph, value: SimpleField) {
        if (value.type.isValue()) {
            builder.append(inheritanceTable.getClassIndex(value.type as ClassType))
        } else {
            // class index is the first field
            builder.append("int(")
            builder.append(memoryName).append('[')
            appendFieldName(graph, value)
            builder.append("])")
        }
    }

}