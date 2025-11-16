package zauber

fun <V : Comparable<V>> V.clamp(min: V, max: V): V {
    return if (this < min) min else if (this < max) this else max
}

fun <V : Comparable<V>> V.min(other: V): V {
    return if (this < other) this else other
}

fun <V : Comparable<V>> V.max(other: V): V {
    return if (this > other) this else other
}

fun <V : Number> V.sq(): V {
    return this * this
}

fun <V : Number> V.pow(power: Int): V {
    // todo handle Int.MIN
    if (power < 0) return 1 / pow(-power)
    var result = 1
    var multiplier = power
    while (power != 0) {
        if (power.and(1) != 0) {
            result *= multiplier
        }
        power = power shr 1
        multiplier *= multiplier
    }
    return result
}

fun <V : Or<Int,Long>> V.pow(power: Int): V {
    require(power >= 0)
    var result = 1
    var multiplier = power
    while (power != 0) {
        if (power.and(1) != 0) {
            result *= multiplier
        }
        power = power shr 1
        multiplier *= multiplier
    }
    return result
}

fun <V : Or<Float, Double>> atan(y: V, x: V): V {
    return native("atan2(y,x)")
}

fun <V : Or<Float, Double>> V.reciprocal(): V {
    return 1f / this
}