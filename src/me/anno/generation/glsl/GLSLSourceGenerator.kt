package me.anno.generation.glsl

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.InheritanceTable
import me.anno.generation.c.CSourceGenerator
import me.anno.generation.llvm.LLVMStructures
import me.anno.generation.structs.Structures.Companion.align
import me.anno.utils.ByteArrayOutputStream2
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isValue
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.interpreting.Instance
import me.anno.zauber.interpreting.Runtime
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
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
        val protectedGlslTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "bool"),

                    Byte to BoxedType("Byte", "int"),
                    Short to BoxedType("Short", "int"),
                    Int to BoxedType("Int", "int"),
                    // todo longs may be supported using extensions
                    // Long to BoxedType("Long", "int64_t"),

                    UByte to BoxedType("Byte", "uint"),
                    UShort to BoxedType("Short", "uint"),
                    UInt to BoxedType("Int", "uint"),
                    // ULong to BoxedType("Long", "uint64_t"),

                    Char to BoxedType("Char", "uint"),
                    // todo half and double are not necessarily supported, and may have to be replaced with float
                    Half to BoxedType("Half", "half"),
                    Float to BoxedType("Float", "float"),
                    Double to BoxedType("Double", "double"),
                )
            }
        }

        val nativeGlslTypes by threadLocal { protectedGlslTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeGlslNumbers by threadLocal { nativeGlslTypes - Types.Boolean }
    }

    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativeGlslNumbers
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativeGlslTypes

    // todo all static-blocks should be executed at compile-time, I think:
    //  because CPU execution is faster than GPU execution
    // todo all references to instances must be replaced with memory indices...
    // todo same for static-blocks

    init {
        // we want nice code
        onlyCheapSimplifications = false
        thisParamName = "_this" // 'this' is reserved
    }

    // todo all strings and such will be entered into this buffer
    val memory = ByteArrayOutputStream2()

    init {
        // ensure not empty & we want no data to have address 0
        writeNullsUntil(64)
    }

    val memoryName = "__memory"
    val gcCounter = 60

    private fun writeNullsUntil(length: Int) {
        while (length > memory.size) {
            memory.write(0)
        }
    }

    fun StringBuilder.appendMemoryAccess(slot: Int): StringBuilder {
        append(memoryName).append('[').append(slot).append(']')
        return this
    }

    fun OutputStream.writeI32(value: Int) {
        // little endian
        write(value)
        write(value shr 8)
        write(value shr 16)
        write(value shr 24)
    }

    val instanceAddress = HashMap<Instance, Int>()

    val structures = LLVMStructures(this)

    private fun getSizeInBytes(instance: Instance): Int {
        val spec = Specialization(instance.clazz.type as ClassType)
        val struct = structures.getStruct(spec)
        return struct.sizeInBytes
    }

    fun getInstanceAddress(instance: Instance): Int {
        return instanceAddress.getOrPut(instance) {
            val ptr = align(memory.size, 4)
            writeNullsUntil(ptr + getSizeInBytes(instance))
            ptr
        }
    }

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        inheritanceTable = InheritanceTable(data)
        generateCodeImpl1(dst, data, mainMethod)
        inheritanceTable.generateFiles(dst)

        // todo inheritance tables belong into memory, too...
        val folder = File(dst.parentFile, "data"); folder.mkdirs()
        memory.writeTo(File(folder, "memory.bin"))
    }

    private fun generateCodeImpl1(dst: File, data: DependencyData, mainMethod: Method) {
        val writer = FileWithImportsWriter(this, dst)
        try {

            defineNullableAnnotation(dst, writer)
            defineMainMethodCall(dst, writer, mainMethod)

            generateCodeImpl(dst, data, writer)

        } finally {
            writer.joinIntoOneGLSLFile(dst)
        }
    }

    private fun FileWithImportsWriter.joinIntoOneGLSLFile(dst: File) {
        val dstFile = File(dst, "ComputeShader.glsl")

        val builder = StringBuilder()
        builder.append("#version 430\n\n")

        val imports = HashSet<String>()
        val written = HashSet<FileEntry>()

        fun handleImports(content: FileEntry) {
            for ((import) in content.imports) {
                if (imports.add(import)) {
                    val keyName = "${import.replace('.', '/')}.h"
                    val srcFile1 = File(dst, keyName)
                    val content1 = newContent[srcFile1]
                    if (content1 != null) {
                        if (written.add(content1)) {
                            handleImports(content1)

                            builder
                                .append("// ").append(keyName).append('\n')
                                .append(content1.content).trimEnd()
                                .append("\n\n")
                        }
                    }
                }
            }
        }

        for ((srcFile, content) in newContent.entries) {
            if (content != null && srcFile.extension == "c") {
                val headerFile = File(srcFile.parentFile, srcFile.nameWithoutExtension + ".h")
                val headerContent = newContent[headerFile]
                if (headerContent != null) {
                    if (written.add(headerContent)) {
                        handleImports(headerContent)

                        builder
                            .append("// ").append(headerFile).append('\n')
                            .append(headerContent.content).trimEnd()
                            .append("\n\n")
                    }
                }

                if (written.add(content)) {
                    handleImports(content)

                    builder
                        .append("// ").append(srcFile).append('\n')
                        .append(content.content).trimEnd()
                        .append("\n\n")
                }
            }
        }

        dstFile.writeText(builder.toString())
    }

    private fun StringBuilder.trimEnd(): StringBuilder {
        while (isNotEmpty() && last().isWhitespace()) setLength(length - 1)
        return this
    }

    override fun appendObjectGetter(
        classScope: Scope, className: String,
        packagePrefix: String, headerOnly: Boolean
    ) {
        nextLine()
        builder.append("uint /* ptr<")
        builder.append(packagePrefix)
        builder.append(className)
        builder.append("> */ __getObject()")

        if (headerOnly) {
            builder.append(';')
            nextLine()
        } else {
            writeBlock {
                // executed at compile time, because running it on the GPU would only cause congestion
                val instance = Runtime.runtime.getObjectInstance(classScope)
                val instanceSlot = getInstanceAddress(instance)
                builder.append("return ").append(instanceSlot).append(';')
                nextLine()
            }
        }
    }

    override fun declareStruct(
        classScope: Scope, className: String,
        packagePrefix: String, fields: Collection<Specialization>
    ) {
        appendClassFlags(classScope)
        builder.append("struct ")
        builder.append(packagePrefix)
        builder.append(className)
        writeBlock {
            // append fields; todo initialize this in constructor
            if (!classScope.isValueType()) {
                builder.append("uint ").append(CLASS_INDEX_NAME).append(';')
                nextLine()
            }
            appendFields(classScope, fields, true, headerOnly = true)
        }
        removeTrailingWhitespace()
        builder.append(";")
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

        val l0 = builder.length
        appendGetObjectInstance(mainMethod.ownerScope, mainMethod.scope)
        val objInstance = builder.substring(l0)
        builder.setLength(l0)

        return FileEntry(emptyList(), this)
            .apply {
                // todo convert argc/argv to String-array, if needed
                content.append(
                    """
                void main() {
                    stdlibMain();
                    $methodName($objInstance);
                }
            """.trimIndent()
                )
            }
    }


}