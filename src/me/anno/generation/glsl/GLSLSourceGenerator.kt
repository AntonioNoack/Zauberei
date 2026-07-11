package me.anno.generation.glsl

import me.anno.generation.BoxedType
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.InheritanceTable
import me.anno.generation.c.CSourceGenerator
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.ByteArrayOutputStream
import java.io.File

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

    val memory = ByteArrayOutputStream()

    init {
        // ensure not empty & we want no data to have address 0
        memory.write(ByteArray(64))
    }

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        inheritanceTable = InheritanceTable(data)
        generateCodeImpl1(dst, data, mainMethod)
        inheritanceTable.generateFiles(dst)

        // todo inheritance tables belong into memory, too...
        val folder = File(dst.parentFile, "data"); folder.mkdirs()
        File(folder, "memory.bin")
            .outputStream().use { fos ->
                memory.writeTo(fos)
            }
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
        for ((srcFile, content) in newContent.entries) {
            if (content != null && srcFile.extension == "c") {
                for ((import) in content.imports) {
                    if (imports.add(import)) {
                        val keyName = "${import.replace('.', '/')}.h"
                        val file = newContent[File(dst, keyName)]
                        if (file != null) {
                            builder
                                .append("// ").append(keyName).append('\n')
                                .append(file.content)
                                .append('\n')
                        }
                    }
                }

                builder
                    .append("// ").append(srcFile).append('\n')
                    .append(content.content)
                    .append('\n')
            }
        }

        dstFile.writeText(builder.toString())
    }


}