package zauber.math

typealias AnyNumber = Byte|UByte|Short|UShort|Int|UInt|Long|ULong|Half|Float|Double

fun <N: AnyNumber> max(a: N, b: N): N {
    return if (a > b) a else b
}

fun <N: AnyNumber> min(a: N, b: N): N {
    return if (a < b) a else b
}
