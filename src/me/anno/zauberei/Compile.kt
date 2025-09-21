package me.anno.zauberei

import me.anno.zauberei.astbuilder.ASTBuilder
import me.anno.zauberei.tokenizer.Tokenizer
import me.anno.zauberei.types.Package
import java.io.File

fun main() {
    // todo compile and run our samples, and confirm all tests work as expected (write unit tests as samples)

    val root = File("./Samples/src")
    val rootLen = root.absolutePath.length

    val rootPackage = Package()

    fun compile(text: String, fileName: String) {
        println("Compiling $text")
        val tokens = Tokenizer(text).tokenize()
        println(tokens)
        val ast = ASTBuilder(tokens, rootPackage).readFileLevel()
    }

    fun addSource(file: File) {
        if (file.isDirectory) {
            for (child in file.listFiles()!!) {
                addSource(child)
            }
        } else if (file.extension == "kt") {
            compile(file.readText(), file.absolutePath.substring(rootLen))
        }
    }

    addSource(root)
    addSource(File(root, "../../src"))

}
