package me.anno.support.cpp.execution

import me.anno.support.cpp.ast.rich.CppASTBuilder
import me.anno.support.cpp.ast.rich.CppStandard
import me.anno.support.cpp.preprocessor.Preprocessor
import me.anno.support.cpp.tokenizer.CppTokenizer
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.parser.ZauberASTClassScanner
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.RuntimeCreate.createByteArray
import me.anno.zauber.interpreting.RuntimeCreate.createInt
import me.anno.zauber.interpreting.RuntimeCreate.createPointer
import me.anno.zauber.interpreting.Stdlib
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.ZauberTokenizer
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Types
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class StbImageTests {

    val header = File("/mnt/LinuxGames/Code/simple-c/stb_image.h")

    /**
     * execute the following stb_image function:
     * stbi_uc *stbi_load_from_memory(stbi_uc const *buffer, int len, int *x, int *y, int *channels_in_file, int desired_channels);
     * */
    @Test
    fun testReadingImageInInterpreter() {

        val scope = root.getOrPut("stbi", ScopeType.PACKAGE)
        fun loadZauber() {
            val zauberTokens = ZauberTokenizer(
                """
            package zauber
            class Pointer<T>(var base: T?, var offset: Long)
            class ClassType<V> {
                external fun sizeof(): Int
            }
        """.trimIndent(), "Stdlib.zbr"
            ).tokenize()
            ZauberASTClassScanner.scanClasses(zauberTokens)
        }

        fun loadC() {
            // load C/C++ code as Zauber
            val files = mapOf(
                header.name to """
                #define STB_IMAGE_IMPLEMENTATION
            """.trimIndent() + header.readText(),
                // needed for declaration:
                "stdio.h" to "",
                "stdlib.h" to """
                typedef struct {
                    char* fileName;
                } FILE;
            """.trimIndent(),
                // needed for implementation:
                "stdarg.h" to "",
                "stddef.h" to "",
                "string.h" to "",
                "limits.h" to "",
                "math.h" to "",
                "assert.h" to "",
                "stdint.h" to "",
                "emmintrin.h" to "",
            )

            val tokenized = files.mapValues { (fileName, source) ->
                CppTokenizer(source, fileName, isCNotCxx = true).tokenize()
            }

            val pp = Preprocessor(tokenized) { _, name -> name }
            val cTokens = pp.preprocess(header.name)

            if (false) File("Preprocess.c")
                .writeText(cTokens.source.toString())

            CppASTBuilder(cTokens, scope, CppStandard.C11).readFileLevel()
        }

        Stdlib.registerAllMethods()
        // zauber must be loaded first, s.t. sizeof() is available at compile-time
        loadZauber()
        // LogManager.enableAllLoggers()
        loadC()

        val methodName = "stbi_load_from_memory"
        val method = scope.methods.first { it.name == methodName }
        println("Method: $method")

        method.removeFlag(Flags.EXTERNAL) // hack

        val bytes = loadSampleImage()
        val buffer = runtime.createByteArray(bytes)
        val size = runtime.createInt(bytes.size)

        // todo how do we allocate/define/use PointerType???
        //  in theory, as long as we don't do maths with it,
        //  it is just a Ref<X>...
        //  but when we do maths with it, we need to store an offset...
        val desiredChannels = runtime.createInt(4)
        val widthPtr = runtime.createPointer(Types.Int, runtime.createInt(0))
        val heightPtr = runtime.createPointer(Types.Int, runtime.createInt(0))
        val channelsPtr = runtime.createPointer(Types.Int, runtime.createInt(0))
        val result = runtime.executeCall2(
            runtime.getObjectInstance(method.ownerScope),
            null, Specialization.fromSimple(method.memberScope), listOf(
                buffer, size, widthPtr, heightPtr, channelsPtr, desiredChannels
            )
        )
        check(result.type.isValue())

        println("Size: $widthPtr x $heightPtr")
    }

    private fun loadSampleImage(): ByteArray {
        val smallImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until smallImage.height) {
            for (x in 0 until smallImage.width) {
                val rx = x - smallImage.width.shr(2)
                val ry = y - smallImage.height.shr(2)
                val color = 255 * 4 * (rx * rx + ry * ry) / (smallImage.width * smallImage.height)
                smallImage.setRGB(x, y, color * 0x10101)
            }
        }
        val smallImageFile = ByteArrayOutputStream()
        ImageIO.write(smallImage, "png", smallImageFile)

        return smallImageFile.toByteArray()
    }

    @Test
    fun testReadingImageWhenCompilingToC() {
        // todo just load it as available bindings,
        //  then use it when actually executing code
    }
}