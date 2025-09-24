package me.anno.zauberei

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.types.NameResolution.resolveNames
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

    fun addSource(text: String, fileName: String) {
        val tokens = Tokenizer(text, fileName).tokenize()
        ASTBuilder(tokens, root).readFileLevel()
    }

    fun addSource(file: File, rootLen: Int = file.absolutePath.length + 1) {
        if (file.isDirectory) {
            for (child in file.listFiles()!!) {
                addSource(child, rootLen)
            }
        } else if (file.extension == "kt") {
            addSource(file.readText(), file.absolutePath.substring(rootLen))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // todo compile and run our samples, and confirm all tests work as expected (write unit tests as samples)

        val t0 = System.nanoTime()

        val project = File(".")
        val samples = File(project, "Samples/src")

        addSource(samples)
        addSource(File(project, "src"))

        if (false) {
            // bonus: compile of Rem's Engine's core module (not working yet, but a few things get parsed)
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

        val t1 = System.nanoTime()
        println("Took ${(t1 - t0) * 1e-6f} ms parsing AST")

        resolveNames(root)

        val t2 = System.nanoTime()
        println("Took ${(t2 - t1) * 1e-6f} ms resolving names")

        resolveTypes(root)

        val t3 = System.nanoTime()
        println("Took ${(t3 - t2) * 1e-6f} ms resolving types")
    }

}