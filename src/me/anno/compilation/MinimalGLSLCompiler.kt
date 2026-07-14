package me.anno.compilation

import me.anno.generation.glsl.GLSLSourceGenerator
import me.anno.utils.StdlibLoader.loadBytes
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import java.io.File

open class MinimalGLSLCompiler(
    val target: GLSLTarget,
    val features: GLSLFeatures = GLSLFeatures()
) : MinimalCompiler(null) {

    init {
        executesObjectsAtCompileTime = true
    }

    // todo we could compile this GLSL to SPIRV

    override fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    ) {
        val gen = GLSLSourceGenerator()
        gen.generateCode(srcFolder, dependencies, mainMethod)
    }

    override fun execute(projectFolder: File): String {

        File(projectFolder, "CMakeLists.txt")
            .writeBytes(loadBytes("files/CMakeLists-GLSL.txt"))

        val srcFolder = File(projectFolder, "src")
        for (fileName in "GLSLComputeShader.c,CStandardFileIO.c,CStandardFileIO.h".split(',')) {
            File(srcFolder, fileName)
                .writeBytes(loadBytes("files/$fileName"))
        }

        val buildFolder = File(projectFolder, "build")
        buildFolder.mkdirs()

        runProcess(buildFolder, "cmake", "..")
        runProcess(buildFolder, "cmake", "--build", ".")

        val programName =
            if (isLinux) "./build/Zauber"
            else "./build/Debug/Zauber.exe"
        return runProcessGetPrinted(projectFolder, programName)
    }
}