package zauber

class Throwable(val message: String)
class Exception(message: String): Throwable(message)
class RuntimeException(message: String) : Exception(message)
class NullPointerException(message: String) : RuntimeException(message)
class IllegalArgumentException(message: String) : RuntimeException(message)

class ClassCastException(): Exception("Cast failed")

/**
 * not yet initialized
 * */
fun throwNJI(name: String) = throw NullPointerException(name)

/**
 * null-pointer exception
 * */
fun throwNPE(message: String) = throw NullPointerException(message)

fun error(message: String) = throw IllegalStateException(message)