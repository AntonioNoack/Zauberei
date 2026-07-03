package me.anno.zauber.types.typeresolution

import me.anno.zauber.types.Types
import me.anno.utils.ResolutionUtils.testTypeResolution
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class LinkedListIssueTest {
    @Test
    fun testInnerCallResolution() {
        val type = testTypeResolution(
            """
        val tested = LinkedList<Int>(1)
        
        package zauber
        fun repeat(count: Int, runnable: () -> Unit) {
            var i = count
            while (i > 0) {
                runnable()
                i--
            }
        }
        
        class LinkedList<V>(capacity: Int = 16) : List<V> {
        
            private val content = Array<V>(capacity)
            private val previous = Array<Int>(capacity)
            private val next = Array<Int>(capacity)
            
            override val size: Int = 0
        
            var head = -1
            var tail = -1
        
            override fun get(index: Int): V {
                return content[getStorageIndex(index)]
            }
        
            private fun getStorageIndex(index: Int): Int {
                if (index < 0) return -1
                var currIndex = head
                repeat(index) {
                    currIndex = next[currIndex]
                }
                return currIndex
            }
        
            override fun iterator(): Iterator<V> {
                return object : Iterator<V> {
                    var externalIndex = 0
                    var nextIndex = getStorageIndex(externalIndex)
                    var prevIndex = getStorageIndex(externalIndex - 1)
                    override fun hasNext(): Boolean = nextIndex >= 0
                    override fun next(): V {
                        prevIndex = nextIndex
                        nextIndex = next[nextIndex]
                        externalIndex++
                        return content[prevIndex]
                    }
                }
            }
        }
            """.trimIndent(), true
        )
        // todo warn the user about open members in final classes
        // todo warn the user about open constructors (impossible)
        assertEquals(Types.LinkedList.withTypeParameter(Types.Int), type)
    }
}