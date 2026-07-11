package me.anno.compilation

import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.wasm.WASMSourceGenerator
import me.anno.utils.StdlibLoader.loadText
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.types.Types
import java.io.File

class MinimalWASMCompiler : MinimalCompiler() {

    companion object {
        val minimalJS by lazy {
            loadText("files/CallWASMFromJS.js")
                .split("IMPORTED_FUNCTIONS")
        }

        /**
         * nvm is cursed, and cannot be called like any other console command
         * */
        fun runModernNode(nodeVersion: String, fileName: String): Array<String> {
            val command = $$"""
                export NVM_DIR="$HOME/.nvm"
                [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
                nvm use $$nodeVersion > /dev/null
                node $$fileName
            """.trimIndent()

            return arrayOf(
                "bash",
                "-lc",
                command
            )
        }
    }

    private fun buildRegisteredMethodsIntoJSFile(builder: StringBuilder) {
        val sample = WASMSourceGenerator()
        for ((key, impl) in JavaSourceGenerator.registeredMethods) {

            val methodName = key.findMethodName(sample)
            val params = key.valueParameterTypes.indices.joinToString("") { index -> ", arg$index" }

            builder.append("\n'")
                .append(methodName)
                .append("': (self").append(params).append(") => {\n")

            // convert UInt and ULong arguments, if they are signed
            for (i in key.valueParameterTypes.indices) {
                val type = key.valueParameterTypes[i]
                when (type) {
                    Types.UInt -> builder.append("arg$i = arg$i < 0 ? BigInt(arg$i) + (1n << 32n) : BigInt(arg$i);\n")
                    Types.ULong -> builder.append("if (arg$i < 0) arg$i += (1n << 64n);\n")
                }
            }

            builder.append(impl)
            if ("return" !in impl) {
                builder.append("\nreturn lib[\"obj_zauber_Unit\"]()\n")
            }
            builder.append("},")
        }
    }

    override fun compile(projectFolder: File, srcFolder: File, dependencies: DependencyData, mainMethod: Method) {

        val builder = StringBuilder()
        builder.append(minimalJS[0])
        buildRegisteredMethodsIntoJSFile(builder)
        builder.append(minimalJS[1])

        File(projectFolder, "CallWASMFromJS.js")
            .writeText(builder.toString())

        val wasmTextFile = File(projectFolder, "Source.wat")
        val wasmBinaryFile = File(projectFolder, "Binary.wasm")

        val gen = WASMSourceGenerator()
        gen.generateCode(wasmTextFile, dependencies, mainMethod)
        gen.binary.out.writeTo(wasmBinaryFile)
    }

    override fun execute(projectFolder: File): String {
        return runProcessGetPrinted(projectFolder, *runModernNode("26", "CallWASMFromJS.js"))
    }
}