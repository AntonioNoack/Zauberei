package zauber
external class Char(val content: Char) {
    external fun compareTo(other: Char): Int
}

interface CharSequence {}

class String(val content: ByteArray) {
    // todo all what we need: charAt(), substring(), trim(), toX(), toXOrNull()

    val length get() = content.size
    val size get() = content.size

    fun get(index: Int) = content[index].toChar()

    fun plus(other: String): String {
        return String(content + other.content)
    }
    fun trim(): String {
        var i0 = 0
        var i1 = length-1
        if (i1 == -1) return ""
        while(this[i0].isWhitespace()) {
            if (i0 == i1) return ""
            i0++
        }
        while(this[i1].isWhitespace()) {
            i1--
        }
        return substring(i0,i1+1)
    }

    fun substring(i0: Int, i1: Int): String {
        // if (i0 == i1) return ""
        // if (i0 == 0 && i1 == length) return this
        return String(content.copyOfRange(i0, i1))
    }

    fun contains(char: Char): Boolean {
        var i = 0
        while (i < length) {
            if (this[i] == char) return true
            i++
        }
        return false
    }
}

external fun println(arg0: String)