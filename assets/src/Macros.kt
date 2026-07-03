package zauber

object MacroContext: Throwable("") {
    lateinit var result: String
    external fun mark(i0: Int, i1: Int, type: String)
    fun <R> parse(tokens: String): R {
        result = tokens
        throw this
    }
}