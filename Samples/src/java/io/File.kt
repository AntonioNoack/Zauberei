package java.io

class File(val absolutePath: String) {
    constructor(parent: File, name: String) : this("$parent/$name")

    external fun readText(): String
    external fun readBytes(): ByteArray
}