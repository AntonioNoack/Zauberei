package zauber
private val printed = StringBuilder()

fun println(value: Char)   { printed.append(value) }
fun println(value: Byte)   { printed.append(value) }
fun println(value: UByte)  { printed.append(value) }
fun println(value: Short)  { printed.append(value) }
fun println(value: UShort) { printed.append(value) }
fun println(value: Int)    { printed.append(value) }
fun println(value: UInt)   { printed.append(value) }
fun println(value: Long)   { printed.append(value) }
fun println(value: ULong)  { printed.append(value) }
fun println(value: Half)   { printed.append(value) }
fun println(value: Float)  { printed.append(value) }
fun println(value: Double) { printed.append(value) }