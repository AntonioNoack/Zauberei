package me.anno.cli.impl

import me.anno.cli.CommandImpl
import me.anno.cli.impl.UnitTestResults.showUnitTestResults
import me.anno.compilation.*
import me.anno.libraries.Library
import me.anno.libraries.Library.Companion.PROJECT_FILE_EXTENSION
import me.anno.libraries.Library.Companion.PROJECT_FILE_NAME
import me.anno.support.Language
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parser.ZauberASTClassScanner
import me.anno.zauber.expansion.Dependencies
import me.anno.zauber.interpreting.Instance
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.TokenList
import me.anno.zauber.tokenizer.TokenType
import me.anno.zauber.tokenizer.ZauberTokenizer
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File
import kotlin.system.exitProcess

// todo load standard library from internal file...

object BuildCommand : CommandImpl("build", "b") {

    private val LOGGER = LogManager.getLogger(BuildCommand::class)
    private val compilers = listOf(
        "java" to MinimalJavaBuildCompiler(),
        "jvm" to MinimalJVMCompiler(),
        "js" to MinimalJavaScriptCompiler(),
        "python" to MinimalPythonCompiler(),
        "c++" to MinimalCppCompiler(),
        "c" to MinimalCCompiler(),
        "llvm" to MinimalLLVMCompiler(),
        "wasm" to MinimalWASMCompiler(),
        "rust" to MinimalRustCompiler(),
        "runtime" to RuntimeCompiler()
    )

    private val zauberExtensions = arrayOf("zauber", "zbr", "kt", "kts")

    override fun execute(options: Options, location: File) {
        val project = readFiles(location, scanAll = "test" in options, options)
        val targetName = options["target"] ?: "runtime" // will be changed to LLVM once stable
        val compiler = compilers.firstOrNull { it.first == targetName }?.second
            ?: error("Invalid target $targetName")

        val projectFolder = project.root
        try {
            buildProject(project, compiler, options)
            if ("test" in options) runTests(project, compiler)
            if ("run" in options) compiler.execute(projectFolder)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if ("debug" in options || '^' !in message /* missing code position */) LOGGER.error("", e)
            else LOGGER.error(message)
            exitProcess(-1)
        }
    }

    private fun markEntryMethodReachable(method: Method) {
        val owner = Specialization.fromSimple(method.ownerScope)
        Dependencies.addClass(owner)

        val constr = method.ownerScope.getOrCreatePrimaryConstructorScope()
        Dependencies.addMethod(Specialization.fromSimple(constr))

        val method0 = Specialization.fromSimple(method.memberScope)
        Dependencies.addMethod(method0)
    }

    private fun markEntryPoints(project: CompileProject, options: Options) {
        // we must mark all entry points reachable
        markEntryMethodReachable(project.mainMethod)
        if ("test" in options) {
            for (test in project.unitTests) {
                markEntryMethodReachable(test)
            }
        }
    }

    private fun buildProject(project: CompileProject, compiler: MinimalCompiler, options: Options) {

        markEntryPoints(project, options)

        val data = Dependencies.collectDependencies()
        val projectFolder = File(project.root, "target")
        val srcFolder = File(projectFolder, "generated").apply { mkdirs() }
        compiler.compile(projectFolder, srcFolder, data, project.mainMethod)
    }

    private val instances = HashMap<Scope, Instance>()
    private fun runTests(project: CompileProject, compiler: MinimalCompiler?) {
        if (compiler == null) {
            instances.clear()
            val testResults = project.unitTests.map { test ->
                test to lazy {
                    val specialization = Specialization.fromSimple(test.memberScope)
                    // todo call beforeEach/beforeAll and afterEach/afterAll if defined
                    val self = getSelfInstance(test.memberScope)
                    runtime.executeCall(
                        self, null,
                        specialization, emptyList(), -1
                    )
                }
            }
            showUnitTestResults(testResults)
        } else {
            TODO("compile and run unit tests")
        }
    }

    private fun getSelfInstance(scope: Scope): Instance {
        return instances.getOrPut(scope) {
            if (scope.isObjectLike()) runtime.getObjectInstance(scope)
            else runtime.getClass(scope.typeWithArgs2).createInstance()
        }
    }

    override fun printHelp() {
        println("Builds the project or file")
        println("  Options:")
        println("    --run: also run the project")
        println("    --test: also run unit tests; you can specify a specific test, too")
        println("    --target: define which target to compile to, options: ${compilers.map { it.first }}")
    }

    // todo load dependencies, too...

    fun readFiles(location: File, scanAll: Boolean, options: Options): CompileProject {
        if (location.isDirectory) {
            // try to find project file & load it
            // the user could also mean to just run all tests in this directory...
            //  -> scan all folders above, too; todo if testing, limit the methods to folders inside
            var folder = location
            while (true) {
                val projectFile = File(folder, PROJECT_FILE_NAME)
                if (projectFile.exists()) {
                    return readFiles(projectFile, scanAll, options)
                }

                folder = folder.parentFile
                    ?: break
            }

            if (!scanAll) {
                error("Missing project file in $location")
            }

            // we still need to find the root file
            //  -> collect all source files, and find the consensus, on what the root file is (2/3 majority)

            val sourceFiles = location.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in zauberExtensions }
                .toList()

            if (sourceFiles.isEmpty()) {
                error("Could not find any source files in $location")
            }

            val projectRoots = sourceFiles.map { findProjectRootFromPackage(it) }
            val uniqueRoots = projectRoots.groupBy { it }
            val biggestRoot = uniqueRoots.maxBy { it.value.size }
            if (biggestRoot.value.size < 2f / 3f * projectRoots.size) {
                val candidates = uniqueRoots.entries.sortedByDescending { it.value.size }.take(3)
                error(
                    "Project has no consensus on what the root file is, " +
                            "best candidates: ${candidates.map { "${it.key} [${(it.value.size * 100) / projectRoots.size}%]" }}"
                )
            }

            val rootI = biggestRoot.key
            val sourceFilesI = sourceFiles.toSourceFiles(rootI)

            LOGGER.info("Project Root: $rootI")
            return registerFilesAndFindMain(rootI, sourceFilesI, options) // dependencies unknown
        } else when (location.extension.lowercase()) {
            in zauberExtensions -> {
                val root = findProjectRootFromPackage(location)
                LOGGER.info("Project Root: $root")

                options["main"] = location.absolutePath
                    .substring(root.absolutePath.length + 1)
                    .withoutExtension()
                LOGGER.info("Main: ${options["main"]}")

                val config = File(root, PROJECT_FILE_NAME)
                if (config.exists()) return readFiles(config, scanAll, options) // for dependencies

                val sourceFiles = collectSourceFiles(root)
                return registerFilesAndFindMain(root, sourceFiles, options) // dependencies unknown
            }
            PROJECT_FILE_EXTENSION -> {
                val project = Library.loadProject(location)
                val root = Library.extractFileFromURI(project.source)
                val sourceFiles = loadAllSources(project)
                return registerFilesAndFindMain(root, sourceFiles, options)
            }
            else -> error("Unknown file extension ${location.extension}")
        }
    }

    private fun loadAllSources(project: Library): List<SourceFile> {
        val roots = HashMap<String, Library>()
        fun discover(library: Library) {
            val oldDep = roots.put(library.name, library)
            if (oldDep != null && oldDep.version > library.version) {
                // oldDep is actually newer -> prefer it
                roots[library.name] = oldDep
                return // dependencies can be ignored
            }

            for (dep in library.dependencies) {
                discover(dep)
            }
        }
        discover(project)

        return roots.flatMap { (_, library) ->
            val libraryRoot = Library.extractFileFromURI(library.source)
            collectSourceFiles(libraryRoot)
        }
    }

    private fun String.withoutExtension(): String {
        return substringBeforeLast('.')
    }

    fun collectSourceFiles(root: File): List<SourceFile> {
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in zauberExtensions }.toList()
            .toSourceFiles(root)
    }

    fun List<File>.toSourceFiles(root: File): List<SourceFile> {
        val si = root.absolutePath.length
        return map { file -> SourceFile(file, file.absolutePath.substring(si)) }
    }

    fun registerFilesAndFindMain(root: File, sourceFiles: List<SourceFile>, options: Options): CompileProject {
        for (file in sourceFiles) {
            val fileName = file.fileName
            val tokenizer = ZauberTokenizer(file.file.readText(), fileName)
            val tokens = tokenizer.tokenize()

            val readLazy = lazy {
                val scanner = ZauberASTClassScanner(tokens, Language.byFileName(fileName))
                scanner.readFileLevel()
            }

            findPackageScopes(tokens) { scope ->
                scope.addInitPart(ScopeInitType.DISCOVER_MEMBERS) {
                    readLazy.value
                }
            }
        }

        val methods = findEntryPoints()
        val mainMethod = selectMainMethod(methods, options)
        val unitTests = methods.filter { method -> method.name != "main" || isUnitTest(method) }
        return CompileProject(root, mainMethod, unitTests)
    }

    fun findPackageScopes(tokens: TokenList, callback: (Scope) -> Unit) {
        // todo skip annotations
        var i = 0
        var lastI = 0
        val size = tokens.size
        var depth = 0
        while (i < size) {
            when {
                tokens.equals(i, TokenType.OPEN_BLOCK) -> depth++
                tokens.equals(i, TokenType.CLOSE_BLOCK) -> depth--
                tokens.equals(i, "package") -> {
                    if (depth == 0) {
                        if (lastI == 0 && i > 0) callback(root)

                        i++ // skip 'package'
                        var path = root
                        while (true) {
                            check(tokens.equals(i, TokenType.NAME, TokenType.KEYWORD))
                            val name = tokens.toString(i)
                            path = path.getOrPut(name, ScopeType.PACKAGE)
                            if (!tokens.equals(i + 1, ".")) break
                            i += 2 // skip name and '.'
                        }
                        callback(path)
                        lastI = i
                    }
                }
            }
            i++
        }
        if (size > 0 && lastI == 0) {
            callback(root)
        }
    }

    fun selectMainMethod(methods: List<Method>, options: Options): Method {
        val candidates = methods.filter { method ->
            method.name == "main" &&
                    method.ownerScope.isObjectLike()
        }
        val main = options["main"]?.split('/', '.')
        if (main != null) {
            val matches0 = candidates.filter { it.ownerScope.path == main }
            val matches1 = matches0.ifEmpty {
                if (main.last() == "main") {
                    val main1 = main.dropLast(1)
                    candidates.filter { it.ownerScope.path == main1 }
                } else emptyList()
            }
            check(matches1.isNotEmpty()) {
                "No matching main method found in $main, candidates: ${matches1.map { it.ownerScope.pathStr }}"
            }
            check(matches1.size == 1) {
                "Main method is ambiguous: $matches1"
            }
            return matches1.first()
        } else {
            if (candidates.isEmpty()) {
                if ("only-test" in options) {
                    return createEmptyMainMethod()
                } else {
                    error("No main method found")
                }
            }
            check(candidates.size == 1) {
                "Please specify which main-method to execute, got $candidates"
            }
            return candidates.first()
        }
    }

    fun findEntryPoints(): List<Method> {
        val result = ArrayList<Method>()
        findEntryPoints(root, result)
        return result
    }

    fun createEmptyMainMethod(): Method {
        val ownerScope = langScope
        val scope = ownerScope.getOrPut("main", ScopeType.METHOD)
        val method = Method(
            null, false, "main",
            emptyList(), emptyList(), scope, Types.Unit,
            emptyList(), ThisExpression(Types.Unit.clazz, scope, -1),
            Flags.SYNTHETIC, -1
        )
        scope.selfAsMethod = method
        return method
    }

    /**
     * scan the file for @Test and fun main()
     * */
    fun findEntryPoints(scope: Scope, result: ArrayList<Method>) {
        // todo flatten recursion like in Rem's Engine

        scope[ScopeInitType.AFTER_DISCOVERY]

        if (scope.isClassLike()) {
            val children = scope.children
            var i = 0
            while (i < children.size) {
                findEntryPoints(children[i++], result)
            }
        } else {
            val asMethod = scope.selfAsMethod
            if (asMethod != null) {
                if (isMainMethod(asMethod) || isUnitTest(asMethod)) {
                    result.add(asMethod)
                }
            }
        }
    }

    private fun isMainMethod(method: Method): Boolean {
        return method.name == "main" && method.ownerScope.isObjectLike()
    }

    private fun isUnitTest(method: Method): Boolean {
        val testTypes = listOf(
            Types.Test,
            Types.ParameterizedTest
        )
        return method.memberScope.annotations.any {
            val annotationType = it.type.resolve() as ClassType
            annotationType in testTypes
        }
    }

    fun findProjectRootFromPackage(file: File): File {
        val file = file.absoluteFile
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine()?.trim() ?: break
                if (line.startsWith("package ")) {
                    val i0 = "package ".length
                    var i = i0
                    while (i < line.length && line[i].run { this.isLetterOrDigit() || this == '.' }) {
                        i++
                    }
                    val segments = line.substring(i0, i).split('.')
                    var folder = file
                    for (segment in segments.asReversed()) {
                        if (segment.isEmpty()) continue
                        folder = folder.parentFile ?: return folder
                        check(folder.name.equals(segment, true)) {
                            "Package/folder-name mismatch"
                        }
                    }
                    return folder.parentFile ?: folder
                }
            }
        }
        LOGGER.warn("Could not find package line in $file, assuming main package")
        return file.parentFile ?: file
    }
}