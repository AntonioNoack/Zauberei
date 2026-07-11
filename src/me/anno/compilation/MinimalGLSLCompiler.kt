package me.anno.compilation

import me.anno.compilation.MinimalCCompiler.Companion.copyCStandardLibTo
import me.anno.generation.c.CSourceGenerator
import me.anno.generation.glsl.GLSLSourceGenerator
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import java.io.File

open class MinimalGLSLCompiler(
    val target: GLSLTarget,
    val features: GLSLFeatures = GLSLFeatures()
) : MinimalCompiler(null) {

    // todo we could compile this GLSL to SPIRV

    companion object {
        val glslStandardLibList = (
                "" +
                        "CStandardLib.h,CStandardLib.c," +
                        "CStandardFileIO.h,CStandardFileIO.c"
                ).split(',')

        val glslStandardLib by lazy {
            glslStandardLibList.associateWith { fileName ->
                MinimalGLSLCompiler::class.java
                    .classLoader.getResourceAsStream("files/$fileName")!!
                    .readBytes()
            }
        }
    }

    override fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    ) {
        val gen = GLSLSourceGenerator()
        gen.generateCode(srcFolder, dependencies, mainMethod)

        copyCStandardLibTo(srcFolder)
    }

    override fun execute(projectFolder: File): String {
        val programName =
            if (isLinux) "./build/Zauber"
            else "./build/Debug/Zauber.exe"
        return runProcessGetPrinted(projectFolder, programName)
    }
}