package me.anno.utils

import jdk.nashorn.internal.objects.NativeMath.min
import kotlin.math.abs
import kotlin.math.max

object StringUtils {
    fun String.capitalize1(): String {
        val first = this[0]
        return if (first in 'a'..'z') {
            val tmp = StringBuilder(length)
            tmp.append(first + ('A' - 'a'))
            tmp.append(this, 1, length)
            tmp.toString()
        } else this
    }

    fun String.iff(condition: Boolean): String {
        return if (condition) this else ""
    }

    fun String.distance(other: String, ignoreCase: Boolean = false): Int {
        if (this == other) return 0
        val sx = this.length + 1
        val sy = other.length + 1
        if (sx <= 1 || sy <= 1) return abs(sx - sy)
        if (sx <= 2 && sy <= 2) return 1
        // switching both sides may be valuable
        if (sx > sy + 5) return other.distance(this, ignoreCase)
        // create cache
        val dist = IntArray(sx * max(sy, 3))
        for (x in 1 until sx) dist[x] = x
        for (y in 1 until sy) {
            var i2 = (y % 3) * sx
            dist[i2++] = y
            var i1 = ((y + 2) % 3) * sx
            var i0 = ((y + 1) % 3) * sx - 1
            val prev1 = other[y - 1]
            for (i in 1 until sx) {
                val prev0 = this[i - 1]
                dist[i2] = when {
                    prev0.equals(prev1, ignoreCase) -> dist[i1]
                    i > 1 && y > 1 &&
                            prev0.equals(other[y - 2], ignoreCase) &&
                            prev1.equals(this[i - 2], ignoreCase) ->
                        min(dist[i0], dist[i2 - 1], dist[i1 + 1]) + 1
                    else -> min(dist[i1], dist[i2 - 1], dist[i1 + 1]) + 1
                }
                i0++
                i1++
                i2++
            }
        }
        val yi = (((sy + 2) % 3) + 1)
        return dist[sx * yi - 1]
    }
}