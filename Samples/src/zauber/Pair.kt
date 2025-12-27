package zauber

data class Pair<First, Second>(val first: First, val second: Second)

infix fun <F, S> F.to(other: S): Pair<F, S> = Pair(this, other)