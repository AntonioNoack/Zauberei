package me.anno.zauber.ast.rich

import me.anno.utils.NumberUtils.pack64
import me.anno.utils.NumberUtils.unpack64I0
import me.anno.utils.NumberUtils.unpack64I1
import me.anno.zauber.logging.LogManager
import me.anno.zauber.tokenizer.TokenList
import kotlin.math.max
import kotlin.math.min

/**
 * stores all global tokens, s.t. we can store code locations in a single Int
 * */
object TokenListIndex {

    private val LOGGER = LogManager.getLogger(TokenListIndex::class)

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
            error("Failed token search of $i in ${indices.toList()}")
        }
        if (false) LOGGER.info("$i${indices.copyOf(tokenLists.size).toList()}->$idx -> ${tokenLists[idx].fileName}")
        return tokenLists[idx]
    }

    fun resolveOrigin(origin: Long): String {
        if (origin < 0) return "(Unknown)"
        val i0 = unpack64I0(origin)
        val i1 = unpack64I1(origin)
        val tl0 = findTokenList(i0)
        return tl0.err(i0 - tl0.tliIndex, i1 - tl0.tliIndex)
    }

    // todo use this for expressions, which are more than one token long
    fun mergeOrigins(k0: Long, k1: Long): Long {
        if (k0 < 0) return k1
        if (k1 < 0) return k0

        val i0 = unpack64I0(k0)
        val tl0 = findTokenList(i0)

        val i2 = unpack64I0(k1)
        val tl1 = findTokenList(i2)
        if (tl0 !== tl1) return k0 // cannot merge them

        val i1 = unpack64I1(k0)
        val i3 = unpack64I1(k1)
        return pack64(min(i0, i2), max(i1, i3))
    }

    fun resolveOriginShort(i: Long): String {
        if (i < 0) return i.toString()
        val i = i.shr(32).toInt()
        val tl = findTokenList(i)
        return tl.errShort(i - tl.tliIndex)
    }
}