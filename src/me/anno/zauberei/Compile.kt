package me.anno.zauberei

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.ASTClassScanner.discoverClasses
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.TypeResolution.resolveTypes
import java.io.File

// todo expand macros:
//   compile-time if
//   compile-time loop (duplicating instructions)
//   compile-time type replacements??? e.g. float -> double

// todo expand hard generics
// todo dependency & type analysis
// todo build output C++
// todo run C++ compiler

object Compile {

    val root = Scope()

    val sources = ArrayList<TokenList>()

    fun addSource(file: File) {
        addSource(file, file.absolutePath.length + 1, root)
    }

    fun addSource(file: File, rootLen: Int, packageScope: Scope) {
        if (file.isDirectory) {
            val scope =
                if (file.absolutePath.length < rootLen) packageScope
                else packageScope.getOrPut(file.name)
            for (child in file.listFiles()!!) {
                addSource(child, rootLen, scope)
            }
        } else if (file.extension == "kt") {
            val text = file.readText()
            val fileName = file.absolutePath.substring(rootLen)
            val source = Tokenizer(text, fileName).tokenize()
            sources.add(source)
            packageScope.sources.add(source)
        }
    }

    fun tokenizeSources() {
        val project = File(".")
        val samples = File(project, "Samples/src")

        // base: compile itself
        addSource(samples)
        addSource(File(project, "src"))

        if (true) {
            // bonus: compile Rem's Engine
            val remsEngine = File(project, "../RemsEngine")
            addSource(File(remsEngine, "src"))
            addSource(File(remsEngine, "Bullet/src"))
            addSource(File(remsEngine, "Box2d/src"))
            addSource(File(remsEngine, "Export/src"))
            addSource(File(remsEngine, "Image/src"))
            addSource(File(remsEngine, "JVM/src"))
            addSource(File(remsEngine, "JOML/src"))
            addSource(File(remsEngine, "Video/src"))
            addSource(File(remsEngine, "Unpack/src"))
        }
    }

    fun collectTopLevelClassesForTypes() {
        for (i in sources.indices) {
            val source = sources[i]
            discoverClasses(source)
        }
    }

    fun buildASTs() {
        for (i in sources.indices) {
            val source = sources[i]
            ASTBuilder(source, root).readFileLevel()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // todo compile and run our samples, and confirm all tests work as expected (write unit tests as samples)

        val t0 = System.nanoTime()

        tokenizeSources()
        val t1 = System.nanoTime()
        println("Took ${(t1 - t0) * 1e-6f} ms Reading & Tokenizing")

        collectTopLevelClassesForTypes()
        val t2 = System.nanoTime()
        println("Took ${(t2 - t1) * 1e-6f} ms Indexing Top-Level Classes")

        buildASTs()
        val t3 = System.nanoTime()
        println("Took ${(t3 - t2) * 1e-6f} ms Parsing AST")

        printPackages(root, 0)

        resolveTypes(root)
        val t4 = System.nanoTime()
        println("Took ${(t4 - t3) * 1e-6f} ms Resolving Types")
    }

    fun printPackages(root: Scope, depth: Int) {
        println("  ".repeat(depth) + root.name)
        for (child in root.children) {
            printPackages(child, depth + 1)
        }
    }

}