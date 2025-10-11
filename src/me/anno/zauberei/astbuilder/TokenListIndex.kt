package me.anno.zauberei.astbuilder

import me.anno.zauberei.tokenizer.TokenList

object TokenListIndex {

    private val tokenLists = ArrayList<TokenList>()
    private var indices = IntArray(64)
    private var totalSize = 0

    private fun clamp(x: Int, min: Int, max: Int): Int {
        return if (x < min) min else if (x < max) x else max
    }

    fun getIndex(tokenList: TokenList, i: Int): Int {
        if (tokenList.tliIndex >= 0) return tokenList.tliIndex + clamp(i, 0, tokenList.totalSize - 1)
        synchronized(this) {
            if (tokenList.tliIndex >= 0) return tokenList.tliIndex + clamp(i, 0, tokenList.totalSize - 1)

            val tli = totalSize
            tokenList.tliIndex = tli
            val idx = tokenLists.size
            tokenLists.add(tokenList)
            if (indices.size == idx) indices = indices.copyOf(idx * 2)
            indices[idx] = tli
            totalSize += tokenList.totalSize
            return tli + clamp(i, 0, tokenList.totalSize - 1)
        }
    }

    fun findTokenList(i: Int): TokenList {
        var idx = indices.binarySearch(i, 0, tokenLists.size)
        if (idx < 0) idx = -idx - 1
        return tokenLists[idx]
    }

    fun resolve(i: Int): String {
        val tl = findTokenList(i)
        return tl.err(i - tl.tliIndex)
    }
}