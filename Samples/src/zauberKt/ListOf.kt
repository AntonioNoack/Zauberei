package zauberKt

fun <V> listOf(vararg v: V): List<V> {
    val dst = ArrayList<V>(1)
    dst.addAll(v)
    return dst
}
