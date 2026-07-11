package me.anno.compilation

import me.anno.generation.rust.RustSourceGenerator
import me.anno.utils.StdlibLoader.loadBytes
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import java.io.File

open class MinimalRustCompiler(preserveFolder: Boolean = false) :
    MinimalCompiler(if (preserveFolder) "ZauberRust" else null) {

    companion object {
        val cargoToml by lazy { loadBytes("files/Cargo.toml") }
    }

    override fun compile(
        projectFolder: File, srcFolder: File,
        dependencies: DependencyData, mainMethod: Method
    ) {
        RustSourceGenerator()
            .generateCode(srcFolder, dependencies, mainMethod)

        File(projectFolder, "Cargo.toml")
            .writeBytes(cargoToml)
    }

    override fun execute(projectFolder: File): String {
        return runProcessGetPrinted(projectFolder, "cargo", "run")
    }
}