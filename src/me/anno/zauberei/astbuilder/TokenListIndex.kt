package me.anno.zauberei.astbuilder

import me.anno.zauberei.tokenizer.TokenList
import kotlin.math.max

object TokenListIndex {

    private val tokenLists = ArrayList<TokenList>()
    private var indices = IntArray(64)
    private var totalSize = 0

    private fun clamp(x: Int, tokenList: TokenList): Int {
        val size = tokenList.totalSize
        return if (x < 0) 0 else if (x >= size) size - 1 else x
    }

    fun getIndex(tokenList: TokenList, i: Int): Int {
        if (tokenList.tliIndex >= 0) return tokenList.tliIndex + clamp(i, tokenList)
        synchronized(this) {
            if (tokenList.tliIndex >= 0) return tokenList.tliIndex + clamp(i, tokenList)

            val tli = totalSize
            tokenList.tliIndex = tli
            val idx = tokenLists.size
            tokenLists.add(tokenList)
            if (indices.size == idx) indices = indices.copyOf(idx * 2)
            indices[idx] = tli
            totalSize += tokenList.totalSize
            return tli + clamp(i, tokenList)
        }
    }

    fun findTokenList(i: Int): TokenList {
        var idx = indices.binarySearch(i, 0, tokenLists.size)
        if (idx < 0) idx = max(-idx - 2, 0)
        if (idx !in tokenLists.indices) {
            throw IllegalStateException("Failed token search of $i in ${indices.toList()}")
        }
        // println("$i${indices.copyOf(tokenLists.size).toList()}->$idx -> ${tokenLists[idx].fileName}")
        return tokenLists[idx]
    }

    fun resolve(i: Int): String {
        val tl = findTokenList(i)
        return tl.err(i - tl.tliIndex)
    }
}