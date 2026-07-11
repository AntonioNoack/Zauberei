package zauber.gc

// todo fully implement and use this in C, C++ and LLVM

value class Page(val capacity: Long, val pointer: Long)

var iteration = 1
val pages = ArrayList<Page>()

fun mark() {
    // todo iterate over all static roots somehow...
    // read their classes
    // iterate over all field slots...
}

external fun readClassIndex(instance: Long): Int

fun mark(instance: Long) {
    val classIndex = readClassIndex(instance)

}

fun sweep() {
    // todo iterate over all pages, checking whether instances can be recycled...

}
