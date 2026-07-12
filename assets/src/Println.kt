package zauber

private val printed = StringBuilder()

fun println(value: Char)   { printed.append(value).append('\n'); flushConsole() }
fun println(value: Byte)   { printed.append(value).append('\n'); flushConsole() }
fun println(value: UByte)  { printed.append(value).append('\n'); flushConsole() }
fun println(value: Short)  { printed.append(value).append('\n'); flushConsole() }
fun println(value: UShort) { printed.append(value).append('\n'); flushConsole() }
fun println(value: Int)    { printed.append(value).append('\n'); flushConsole() }
fun println(value: UInt)   { printed.append(value).append('\n'); flushConsole() }
fun println(value: Long)   { printed.append(value).append('\n'); flushConsole() }
fun println(value: ULong)  { printed.append(value).append('\n'); flushConsole() }
fun println(value: Half)   { printed.append(value).append('\n'); flushConsole() }
fun println(value: Float)  { printed.append(value).append('\n'); flushConsole() }
fun println(value: Double) { printed.append(value).append('\n'); flushConsole() }

fun println(instance: Any?) {
    if (instance == null) printed.append("null\n")
    else printed.append(instance).append('\n')

    flushConsole()
}