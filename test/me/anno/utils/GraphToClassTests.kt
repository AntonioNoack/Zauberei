package me.anno.utils

import me.anno.generation.WASMGenerationTests
import me.anno.zauber.logging.LogManager
import org.junit.jupiter.api.Test

class GraphToClassTests {
    @Test
    fun testGraphToClass() {
        LogManager.enable("ASTSimplifier")
        // todo make this code complex enough, s.t. simplifyTree is no longer sufficient
        val code = """
        fun classifyNumber(n: Int): Int {
            if (n < 0) return -1
            if (n == 0) return 0
        
            var score = 0
        
            while (score < 5) {
                if (n % 2 == 0) {
                    score += 2
                } else {
                    score += 1
                }
            
                if (n % 3 == 0) {
                    score += 3
                }
            
                if (n % 5 == 0) {
                    score += 5
                }
            
                var digitSum = 0
                var temp = n
            
                /*while (temp > 0) {
                    digitSum += temp % 10
                    temp /= 10
                }*/
            
                when {
                    digitSum > 30 -> score += 4
                    digitSum > 15 -> score += 2
                    else -> score += 1
                }
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