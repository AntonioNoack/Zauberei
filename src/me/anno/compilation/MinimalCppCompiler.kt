package me.anno.compilation

import me.anno.generation.cpp.CppSourceGenerator
import me.anno.utils.StdlibLoader.loadBytes
import me.anno.utils.StdlibLoader.loadText
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import java.io.File

open class MinimalCppCompiler(preserveFolder: Boolean = false) :
    MinimalCompiler(if (preserveFolder) "ZauberCpp" else null) {

    companion object {
        val minimalCMakeLists by lazy {
            loadText("files/CMakeLists.txt")
        }
    }

    override fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    ) {
        val gen = CppSourceGenerator()
        gen.generateCode(srcFolder, dependencies, mainMethod)

        val si = projectFolder.absolutePath.length + 1
        val filesList = gen.cppFiles.joinToString("\n") { file ->
            file.absolutePath.substring(si)
        }

        File(projectFolder, "CMakeLists.txt")
            .writeText(
                minimalCMakeLists
                    .replace("FILES_LIST", filesList)
                    .replace(
                        "DEFINITIONS",
                        if (Specialization(Types.String) in dependencies.createdClasses) "HAS_STRINGS" else ""
                    )
            )

        File(srcFolder, "CppStandardLib.cpp")
            .writeBytes(loadBytes("files/CppStandardLib.cpp"))

        File(srcFolder, "CppStandardLib.hpp")
            .writeBytes(loadBytes("files/CppStandardLib.hpp"))

        val buildFolder = File(projectFolder, "build")
        buildFolder.mkdirs()

        runProcess(buildFolder, "cmake", "..")
        runProcess(buildFolder, "cmake", "--build", ".")
    }

    override fun execute(projectFolder: File): String {
        val buildFolder = File(projectFolder, "build")
        val programName =
            if (isLinux) "./Zauber"
            else "./Debug/Zauber.exe"
        return runProcessGetPrinted(buildFolder, programName)
    }
}