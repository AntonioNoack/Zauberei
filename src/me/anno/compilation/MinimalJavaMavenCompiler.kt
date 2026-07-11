package me.anno.compilation

import me.anno.generation.java.JavaSourceGenerator
import me.anno.utils.StdlibLoader.loadBytes
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import java.io.File

class MinimalJavaMavenCompiler : MinimalCompiler() {
    companion object {
        val minimalPom by lazy {
            loadBytes("files/minimal.pom")
        }
    }

    override fun compile(projectFolder: File, srcFolder: File, dependencies: DependencyData, mainMethod: Method) {
        JavaSourceGenerator()
            .generateCode(srcFolder, dependencies, mainMethod)

        // generate simple maven file
        val pom = File(projectFolder, "pom.xml")
        pom.writeBytes(minimalPom)

        // compile it
        runProcess(projectFolder, "mvn", "clean", "install")
    }

    override fun execute(projectFolder: File): String {
        // run it
        val jarFile = File(projectFolder, "target/minimal-1.0-SNAPSHOT.jar")
        return runProcessGetPrinted(projectFolder, "java", "-jar", jarFile.absolutePath)
    }
}