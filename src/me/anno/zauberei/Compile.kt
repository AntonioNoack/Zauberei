package me.anno.zauberei

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.types.Package
import java.io.File

fun main() {
    // todo compile and run our samples, and confirm all tests work as expected (write unit tests as samples)

    val project = File(".")
    val root = File(project, "Samples/src")

    val rootPackage = Package()

    fun compile(text: String, fileName: String) {
        val tokens = Tokenizer(text, fileName).tokenize()
        println(tokens)
        ASTBuilder(tokens, rootPackage).readFileLevel()
        // todo expand macros
        // todo expand hard generics
        // todo dependency & type analysis
        // todo build output C++
        // todo run C++ compiler
    }

    fun addSource(file: File, rootLen: Int = file.absolutePath.length + 1) {
        if (file.isDirectory) {
            for (child in file.listFiles()!!) {
                addSource(child, rootLen)
            }
        } else if (file.extension == "kt") {
            compile(file.readText(), file.absolutePath.substring(rootLen))
        }
    }

    addSource(root)
    addSource(File(project, "src"))

    val remsEngine = File(project, "../RemsEngine")
    addSource(File(remsEngine, "src"))

}
