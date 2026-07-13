package me.anno.utils

import me.anno.generation.InheritanceTable
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.rich.parser.ZauberASTClassScanner
import me.anno.zauber.tokenizer.ZauberTokenizer

object StdlibLoader {
    fun loadBytes(fileName: String): ByteArray {
        val inputStream = InheritanceTable::class.java
            .classLoader.getResourceAsStream(fileName)
            ?: error("Missing resource '$fileName'")
        return inputStream.readBytes()
    }

    fun loadText(fileName: String): String {
        return loadBytes(fileName).decodeToString()
    }

    fun loadCode(fileName: String) {
        val src = loadText(fileName)
        val tokens = ZauberTokenizer(src, fileName).tokenize()
        ZauberASTClassScanner.scanClasses(tokens)
    }

    fun loadLazyCode(fileName: String): ResetThreadLocal<Unit> {
        return threadLocal { loadCode(fileName) }
    }
}