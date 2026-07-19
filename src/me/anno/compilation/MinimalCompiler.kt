package me.anno.compilation

import me.anno.utils.ResolutionUtils
import me.anno.utils.ResolutionUtils.printDependencies
import me.anno.utils.assertEquals
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.Dependencies
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.types.Specialization
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * base class for toy compilers,
 * compiling Zauber to other languages
 * */
abstract class MinimalCompiler(val preserveFolderName: String? = null) {

    companion object {

        private val LOGGER = LogManager.getLogger(MinimalCompiler::class)

        val isLinux: Boolean =
            System.getProperty("os.name")
                .contains("linux", true)
    }

    fun InputStream.printToThread(showLine: (String) -> Unit) {
        thread {
            val reader = bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                showLine(line)
            }
        }
    }

    fun runProcess(folder: File, vararg params: String) {
        val jvmProcess = ProcessBuilder(*params)
            .directory(folder)
            .start()
        jvmProcess.inputStream.printToThread(LOGGER::info)
        jvmProcess.errorStream.printToThread(LOGGER::error)
        assertEquals(0, jvmProcess.waitFor()) { "Run(${params.joinToString()}) Failed" }
    }

    fun runProcessGetPrinted(folder: File, vararg params: String): String {
        check(params.isNotEmpty()) { "Cannot run empty command" }
        val jvmProcess = ProcessBuilder(*params)
            .directory(folder)
            .start()
        jvmProcess.errorStream.printToThread(LOGGER::error)
        val printed = jvmProcess.inputStream.readBytes().decodeToString()
        assertEquals(0, jvmProcess.waitFor()) { "Run(${params.joinToString()}) Failed" }
        return printed
    }

    fun registerMainMethod(testScope: Scope) {
        val method = testScope[ScopeInitType.AFTER_DISCOVERY].methods.first { it.name == "main" }
        Dependencies.addMethod(Specialization.fromSimple(method.memberScope))
    }

    var executesObjectsAtCompileTime = false

    open fun testCompileMainAndRun(code: String, registerMethods: () -> Unit): String {

        LOGGER.info("Starting compilation")

        Dependencies.collectObjectConstructors = !executesObjectsAtCompileTime

        val testScope = ResolutionUtils.typeResolveScope(code)
        registerMainMethod(testScope)

        val dependencies = Dependencies.collectDependencies()
        printDependencies(dependencies)

        // prepare folders
        val projectFolder = File(
            System.getProperty("user.home"),
            if (preserveFolderName == null) "Desktop/Zauber"
            else "Desktop/${preserveFolderName}"
        )

        if (preserveFolderName == null) projectFolder.makeCleanFolder()
        projectFolder.mkdirs()

        val srcFolder = File(projectFolder, "src")
        // srcFolder should be cleaned up by DeltaFileWriter
        srcFolder.mkdirs()

        registerMethods()

        val mainMethod = testScope.methods.first { it.name == "main" }
        compile(projectFolder, srcFolder, dependencies, mainMethod)

        return execute(projectFolder)
    }

    fun File.makeCleanFolder() {
        // avoid deleting folders, because some terminals (VSCode) stick to their ID, not their path
        if (exists() && isDirectory) {
            for (child in listFiles() ?: return) {
                child.deleteRecursively()
            }
        } else {
            if (exists()) delete()
            mkdirs()
        }
    }

    abstract fun execute(projectFolder: File): String
    abstract fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    )
}