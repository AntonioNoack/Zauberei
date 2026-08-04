package me.anno.utils

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max

class ByteArrayOutputStream2 : OutputStream() {

    var bytes = ByteArray(8192)
    var size = 0

    override fun write(p0: Int) {
        ensureExtra(1)
        bytes[size++] = p0.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureExtra(len)
        b.copyInto(bytes, size, off, off + len)
        size += len
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    fun align(alignment: Int) {
        val mask = alignment - 1
        check(alignment.and(mask) == 0)
        while ((size.and(mask)) != 0) {
            write(0)
        }
    }

    fun ensureExtra(extraSize: Int) {
        val requiredSize = size + extraSize
        if (requiredSize >= bytes.size) {
            val newSize = max(bytes.size * 2, requiredSize)
            bytes = bytes.copyOf(newSize)
        }
    }

    fun writeTo(dst: File) {
        FileOutputStream(dst).use { fos ->
            writeTo(fos)
        }
    }

    fun writeTo(dst: OutputStream) {
        dst.write(bytes, 0, size)
    }

    fun removeSection(i0: Int, i1: Int) {
        check(i0 >= 0)
        check(i1 >= i0)
        check(i1 <= size)
        bytes.copyInto(bytes, i0, i1, size)
        size -= (i1 - i0)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}