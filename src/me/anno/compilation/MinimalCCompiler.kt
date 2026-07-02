package me.anno.compilation

import me.anno.generation.c.CSourceGenerator
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import java.io.File

open class MinimalCCompiler :
    MinimalCompiler(null) {

    companion object {
        val minimalCMakeListsForC by lazy {
            MinimalCCompiler::class.java
                .classLoader.getResourceAsStream("files/CMakeLists-C.txt")!!
                .readBytes().decodeToString()
        }

        val cStandardLibList = (
                "" +
                        "CStandardLib.h,CStandardLib.c," +
                        "CStandardFileIO.h,CStandardFileIO.c"
                ).split(',')

        val cStandardLib by lazy {
            cStandardLibList.associateWith { fileName ->
                MinimalCCompiler::class.java
                    .classLoader.getResourceAsStream("files/$fileName")!!
                    .readBytes()
            }
        }

        fun copyCStandardLibTo(srcFolder: File) {
            for ((fileName, content) in cStandardLib) {
                File(srcFolder, fileName)
                    .writeBytes(content)
            }
        }
    }

    override fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    ) {
        val gen = CSourceGenerator()
        gen.generateCode(srcFolder, dependencies, mainMethod)

        val si = projectFolder.absolutePath.length + 1
        val filesList = gen.cppFiles.joinToString("\n") { file ->
            file.absolutePath.substring(si)
        }

        File(projectFolder, "CMakeLists.txt")
            .writeText(
                minimalCMakeListsForC
                    .replace("FILES_LIST", filesList)
                    .replace(
                        "DEFINITIONS",
                        if (Specialization(Types.String) in dependencies.createdClasses) "HAS_STRINGS" else ""
                    )
                    .replace(
                        "FIND_PACKAGES",
                        libraries.entries.joinToString("\n") { (name, _) ->
                            "find_package($name REQUIRED)"
                        })
                    .replace(
                        "LINK_WITH_LIBRARIES",
                        "target_link_libraries(Zauber PRIVATE $nativeLibraries${
                            libraries.values.flatten().distinct()
                                .joinToString("") { name -> "\n$name" }
                        })"
                    )
            )

        copyCStandardLibTo(srcFolder)

        val buildFolder = File(projectFolder, "build")
        buildFolder.mkdirs()

        runProcess(buildFolder, "cmake", "..")
        runProcess(buildFolder, "cmake", "--build", ".")
    }

    private val libraries = LinkedHashMap<String, List<String>>()
    private var nativeLibraries = "m" // m = Math

    fun addCMakeLibrary(name: String, vararg libraryNames: String) {
        libraries[name] = libraryNames.asList()
    }

    override fun execute(projectFolder: File): String {
        val programName =
            if (isLinux) "./build/Zauber"
            else "./build/Debug/Zauber.exe"
        return runProcessGetPrinted(projectFolder, programName)
    }
}