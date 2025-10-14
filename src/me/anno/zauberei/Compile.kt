package me.anno.zauberei

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.astbuilder.ASTClassScanner.findNamedClasses
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.typeresolution.TypeResolution.forEachScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveTypesAndNames
import me.anno.zauberei.types.Scope
import java.io.File

// todo we need type resolution, but it is really hard
//  can we outsource the problem?
//  should we try to solve easy problems, first?
//  should we implement an easier language compiler like Java first?
//  can we put all work on the C++/Zig compiler???
//  should we try to create a Kotlin-preprocessor instead?

// todo if-conditions help us find what type something is...
//  but we need to understand what's possible and how the code flows...

// todo most type resolution probably is easy,
//  e.g. function names usually differ from field names,
//  so once we know all function names known to a scope (or everything), we can already decide many cases

// todo expand macros:
//   compile-time if
//   compile-time loop (duplicating instructions)
//   compile-time type replacements??? e.g. float -> double

// todo like Zig, just import .h/.hpp files, and use their types and functions

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
                else packageScope.getOrPut(file.name, null)
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

        if (false) {
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

    fun collectNamedClassesForTypeResolution() {
        for (i in sources.indices) {
            val source = sources[i]
            findNamedClasses(source)
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

        collectNamedClassesForTypeResolution()
        val t2 = System.nanoTime()
        println("Took ${(t2 - t1) * 1e-6f} ms Indexing Top-Level Classes")

        buildASTs()
        val t3 = System.nanoTime()
        println("Took ${(t3 - t2) * 1e-6f} ms Parsing AST")

        if (false) printPackages(root, 0)

        // 658k expressions 😲 (1µs/element at the moment)

        resolveTypesAndNames(root)
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