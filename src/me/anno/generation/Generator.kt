package me.anno.generation

import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.expansion.DependencyData
import java.io.File

// todo we could compile to other languages, too,
//  to make interop easier, e.g. if we wanted to write something in PyTorch, just with Kotlin/Zauber as the style,
//  -> implement a JavaScript and a Python backend, too :3 -> surely they will share lots of logic

abstract class Generator(val blockSuffix: String = "}\n") {

    val builder = StringBuilder()

    var indentation = 0
    fun indent() {
        repeat(indentation) { builder.append("  ") }
    }

    var commentDepth = 0
    open fun comment(body: () -> Unit) {
        commentDepth++
        try {
            builder.append(if (commentDepth == 1) "/* " else "(")
            body()
            removeTrailingWhitespace()
            builder.append(if (commentDepth == 1) " */" else ")")
        } finally {
            commentDepth--
        }
    }

    fun removeTrailingWhitespace() {
        var lastIndex = builder.lastIndex
        while (lastIndex >= 0 && builder[lastIndex].isWhitespace()) {
            lastIndex--
        }
        builder.setLength(lastIndex + 1)
    }

    fun openBlock() {
        indent()
        indentation++
    }

    fun closeBlock() {
        indentation--
        indent()
    }

    fun nextLine() {
        builder.append('\n')
        indent()
    }

    fun appendLines(text: String) {
        for (line in text.lines()) {
            builder.append(line)
            nextLine()
        }
    }

    open fun writeBlock(run: () -> Unit) {
        if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
        builder.append("{")

        indentation++
        nextLine()

        try {
            run()

            dedent()

            indentation--
            builder.append("}\n")
            indent()
            indentation++

        } finally {
            indentation--
        }
    }

    fun finish(): String {
        val str = builder.toString()
        builder.clear()
        return str
    }

    fun block(run: () -> Unit) {
        openBlock()
        run()
        closeBlock()
        builder.append(blockSuffix)
    }

    fun dedent() {
        if (builder.endsWith("  ")) {
            builder.setLength(builder.length - 2)
        }
    }

    open fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        throw NotImplementedError("${javaClass.simpleName}.generateCode()")
    }
}
