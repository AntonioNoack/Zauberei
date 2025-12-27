package zauber

class IntRange(val from: Int, val endExcl: Int, val step: Int) : Iterable<Int> {
    constructor(from: Int, endExcl: Int) : this(from, endExcl, 1)

    override fun iterator(): Iterator<Int> = IntRangeIterator(from, endExcl)
}

class IntRangeIterator(var index: Int, val endExcl: Int, val step: Int = 1) : Iterator<Int> {
    override fun hasNext(): Boolean = index < endExcl
    override fun next(): Int {
        val value = index
        index += step
        return value
    }
}

infix fun Int.to(endIncl: Int): IntRange {
    return IntRange(this, endIncl + 1, 1)
}

infix fun Int.until(endExcl: Int): IntRange {
    return IntRange(this, endExcl, 1)
}

operator fun Int.rangeTo(endIncl: Int): IntRange {
    return IntRange(this, endIncl + 1, 1)
}

operator fun Int.rangeUntil(endExcl: Int): IntRange {
    return IntRange(this, endExcl, 1)
}
