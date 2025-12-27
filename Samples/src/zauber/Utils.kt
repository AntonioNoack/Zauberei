package zauber

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

typealias SomeInt = Byte|Short|Int|Long
typealias SomeFloat = Half|Float|Double
typealias SomeNumber = Int|Long

fun <N : SomeNumber> N.pow(power: Int): N {
    require(power >= 0)
    var result: N = 1
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

fun <N : SomeFloat> atan(y: N, x: N): N {
    return native("atan2(y,x)")
}

fun <N : SomeFloat> N.reciprocal(): N {
    return 1f / this
}