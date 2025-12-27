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

fun <N : Number> N.pow(power: Int): N {
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

fun <N : Or4<Byte, Short, Int, Long>> N.pow(power: Int): N {
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

fun <N : Or<Float, Double>> atan(y: N, x: N): N {
    return native("atan2(y,x)")
}

fun <N : Or<Float, Double>> N.reciprocal(): N {
    return 1f / this
}