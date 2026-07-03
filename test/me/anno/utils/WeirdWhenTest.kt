package me.anno.utils

import me.anno.generation.WASMGenerationTests
import me.anno.zauber.logging.LogManager
import org.junit.jupiter.api.Test

class WeirdWhenTest {

    @Test
    fun testWeirdIfElse() {
        // fixed :)
        LogManager.enable("ASTSimplifier")
        val code = """
        fun main() {
            println(if (false) 2 else 1)
        }
        """.trimIndent()

        val printed = WASMGenerationTests().generator()
            .testCompileMainAndRun(code) {}
        assertEquals("1\n", printed)
    }

    @Test
    fun testWeirdWhenCase() {
        // how does this return 0???
        //  the return and println somehow is on b0...
        // -> ResolvedSetField on backing fields used the wrong block: when it branched, it didn't take any branches
        LogManager.enable("ASTSimplifier")
        val code = """
        fun main() {
            var score = 0
            val value = when {
                score >= 12 -> 2
                else -> 1
            }
            println(value)
        }
        """.trimIndent()

        val printed = WASMGenerationTests().generator()
            .testCompileMainAndRun(code) {}
        assertEquals("1\n", printed)
    }

    @Test
    fun testWeirdWhen2() {
        // this returned 0, too
        val code = """
        fun classifyNumber(n: Int): Int {
            var score = 0
        
            if (n % 2 == 0) {
                score += 2
            } else {
                score += 1
            }
        
            if (n % 5 == 0) {
                score += 5
            }
            
            return when {
                score >= 12 -> 4
                score >= 8 -> 3
                score >= 4 -> 2
                else -> 1
            }
        }
        fun main() {
            println(classifyNumber(7))
        }
        """.trimIndent()

        val printed = WASMGenerationTests().generator()
            .testCompileMainAndRun(code) {}
        assertEquals("1\n", printed)
    }
}