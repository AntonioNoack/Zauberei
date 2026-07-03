package me.anno.other

import me.anno.generation.CGenerationTests
import me.anno.zauber.interpreting.Stdlib
import me.anno.zauber.logging.LogManager

fun main() {

    // todo we should auto-generate external functions, types and values from glfw3.h

    val code = """
        
typealias GLenum = UInt

@CInclude("<GLFW/glfw3.h>")
external val GLFW_CONTEXT_VERSION_MAJOR: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GLFW_CONTEXT_VERSION_MINOR: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_VERTEX_SHADER: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_FRAGMENT_SHADER: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_ARRAY_BUFFER: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_STATIC_DRAW: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_COLOR_BUFFER_BIT: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_FLOAT: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_FALSE: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_TRUE: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_TRIANGLES: GLenum

@CInclude("<GLFW/glfw3.h>")
external val GL_COMPILE_STATUS: GLenum

@CInclude("<stdio.h>")
external val stderr: Int

@CInclude("<stdio.h>")
external fun fprintf(output: Int, template: CString, arg0: NativePointer)

@CInclude("<GLFW/glfw3.h>")
external value class GLEnum(val content: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glGenBuffers(count: Int, dst: Ref<UInt>)

@CInclude("<GLFW/glfw3.h>")
external fun glBindBuffer(target: GLenum, buffer: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glBufferData(target: GLenum, size: Int, data: NativePointer?, allocationHint: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glEnableVertexAttribArray(buffer: Int)

@CInclude("<GLFW/glfw3.h>")
external fun glDisableVertexAttribArray(buffer: Int)

@CInclude("<GLFW/glfw3.h>")
external fun glVertexAttribPointer(buffer: Int, numComp: Int, type: GLenum, normalized: GLenum, stride: Int, offset: Long)

@CInclude("<GLFW/glfw3.h>")
external fun glVertexAttribPointer(buffer: Int, numComp: Int, type: GLenum, normalized: GLenum, stride: Int, offset: NativePointer?)

@CInclude("<GLFW/glfw3.h>")
external fun glCreateProgram(): UInt

@CInclude("<GLFW/glfw3.h>")
external fun glAttachShader(program: UInt, shader: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glLinkProgram(program: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glUseProgram(program: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glCreateShader(type: GLenum): UInt

@CInclude("<GLFW/glfw3.h>")
external fun glShaderSource(shader: UInt, count: Int, src: CString, unused: NativePointer?)

@CInclude("<GLFW/glfw3.h>")
external fun glCompileShader(shader: UInt)

@CInclude("<GLFW/glfw3.h>")
external fun glGetAttribLocation(program: UInt, name: CString): Int

@CInclude("<GLFW/glfw3.h>")
external fun glDrawArrays(primitive: GLenum, offset: Int, count: Int)

@CInclude("<GLFW/glfw3.h>")
external fun glfwCreateWindow(x: Int, y: Int, name: CString, unused1: NativePointer?, unused2: NativePointer?): NativePointer

@CInclude("<GLFW/glfw3.h>")
external fun glGetShaderiv(shader: UInt, type: GLenum, result: Ref<UInt>)

@CInclude("<GLFW/glfw3.h>")
external fun glfwInit(): Boolean

@CInclude("<GLFW/glfw3.h>")
external fun glClear(flags: GLenum)

@CInclude("<GLFW/glfw3.h>")
external fun glShaderSource(shader: UInt, count: Int, src: CString, unused: NativePointer?)

@CInclude("<GLFW/glfw3.h>")
external fun glGetShaderInfoLog(shader: UInt, count: Int, unknown: NativePointer?, dst: NativePointer?)

@CInclude("<GLFW/glfw3.h>")
external fun glfwMakeContextCurrent(window: NativePointer)

@CInclude("<GLFW/glfw3.h>")
external fun glfwWindowShouldClose(window: NativePointer): Boolean

@CInclude("<GLFW/glfw3.h>")
external fun glfwSwapBuffers(window: NativePointer)

@CInclude("<GLFW/glfw3.h>")
external fun glfwWindowHint(flag: GLenum, value: Int)

@CInclude("<GLFW/glfw3.h>")
external fun glfwPollEvents()

@CInclude("<GLFW/glfw3.h>")
external fun glfwTerminate()

/*
    #include <stdio.h>
    #include <stdlib.h>

    #define GL_GLEXT_PROTOTYPES
    #include <GLFW/glfw3.h>
*/

val vertex_shader_src =
        "#version 110\n" +
        "attribute vec2 pos;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(pos, 0.0, 1.0);\n" +
        "}\n"

val fragment_shader_src =
        "#version 110\n" +
        "void main() {\n" +
        "    gl_FragColor = vec4(1.0, 0.5, 0.2, 1.0);\n" +
        "}\n"

fun compile_shader(type: GLenum, src: String): UInt {
    val s: UInt = glCreateShader(type)
    glShaderSource(s, 1, src.toCString(), null)
    glCompileShader(s)

    val ok = Ref<UInt>(0u);
    glGetShaderiv(s, GL_COMPILE_STATUS, ok)

    if (ok.value == 0u) {
        val log = Array<Byte>(512)
        glGetShaderInfoLog(s, log.size, null, log.getDataPointer())
        fprintf(stderr, "shader error: %s\n".toCString(), log.getDataPointer())
        exitProcess(1)
    }
    return s
}


fun main() {
    if (!glfwInit()) exitProcess(1)

    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0)

    val window = glfwCreateWindow(800, 600, "Triangle".toCString(), null, null)
    if (window == null) {
        glfwTerminate()
        exitProcess(1)
    }

    glfwMakeContextCurrent(window)

    val vs = compile_shader(GL_VERTEX_SHADER, vertex_shader_src)
    val fs = compile_shader(GL_FRAGMENT_SHADER, fragment_shader_src)

    val program = glCreateProgram()
    glAttachShader(program, vs)
    glAttachShader(program, fs)
    glLinkProgram(program)

    val vertices = arrayOf<Float>(
         0.0f,  0.5f,
        -0.5f, -0.5f,
         0.5f, -0.5f
    );

    val vbo = Ref<UInt>(0u)
    glGenBuffers(1, vbo)

    glBindBuffer(GL_ARRAY_BUFFER, vbo.value);
    glBufferData(GL_ARRAY_BUFFER, vertices.size * 4, vertices.getDataPointer(), GL_STATIC_DRAW)

    val pos_attrib = glGetAttribLocation(program, "pos".toCString())

    while (!glfwWindowShouldClose(window)) {

        glClear(GL_COLOR_BUFFER_BIT)

        glUseProgram(program)

        glBindBuffer(GL_ARRAY_BUFFER, vbo.value)
        glEnableVertexAttribArray(pos_attrib)
        glVertexAttribPointer(
                pos_attrib, 2,
                GL_FLOAT, GL_FALSE, 0, null
        );

        glDrawArrays(GL_TRIANGLES, 0, 3)

        glDisableVertexAttribArray(pos_attrib)
        glfwSwapBuffers(window)
        glfwPollEvents()
   }

   glfwTerminate()
}

package zauber
class Ref<V>(var value: V) {
    fun equals(other: Ref<*>?): Boolean = this === other
}

typealias CString = Ref<Byte>
typealias NativePointer = Ref<Nothing?>

fun String.toCString(): CString = content.content

@CInclude("<stdlib.h>")
private external fun exit(exitCode: Int)

fun exitProcess(exitCode: Int) {
    exit(exitCode)
}

annotation class CInclude(val source: String)

    """.trimIndent()

    // todo bug: why can Ref(0u) not be resolved???

    // todo Ref is an issue: the data would be read at ~classIndex, but we want it at ~value
    //  -> shall we move all pointers over classIndex? would be a possibility...

    LogManager.enableAllLoggers()

    CGenerationTests().apply {
        val gen = generator()
        gen.addCMakeLibrary("glfw3", "glfw", "GL")
        gen.testCompileMainAndRun(code) {
            Stdlib.registerAllMethods()
        }
    }
}