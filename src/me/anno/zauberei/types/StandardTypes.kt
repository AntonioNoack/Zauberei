package me.anno.zauberei.types

import me.anno.zauberei.Compile.root

/**
 * types, that are automatically imported into every file
 * */
object StandardTypes {
    val standardTypes = mapOf(
        // strings
        "String" to "kotlin",
        "StringBuilder" to "kotlin",
        "CharSequence" to "kotlin",

        // special types
        "Any" to "kotlin",
        "Nothing" to "kotlin",
        "Unit" to "kotlin",
        "Array" to "kotlin",

        // util
        "Class" to "kotlin",
        "Enum" to "kotlin",
        "IntRange" to "kotlin",
        "Lazy" to "kotlin",

        "Comparable" to "kotlin",
        "Comparator" to "kotlin",

        "Iterator" to "kotlin",
        "Iterable" to "kotlin",
        "Collection" to "kotlin",
        "MutableCollection" to "kotlin",

        "List" to "kotlin",
        "ArrayList" to "kotlin",
        "MutableList" to "kotlin",

        "IndexedValue" to "kotlin",

        "Set" to "kotlin",
        "HashSet" to "kotlin",
        "MutableSet" to "kotlin",

        "Map" to "kotlin",
        "HashMap" to "kotlin",
        "MutableMap" to "kotlin",

        "Annotation" to "kotlin",
        "Suppress" to "kotlin",
        "Deprecated" to "kotlin",

        "Throwable" to "kotlin",
        "Exception" to "kotlin",
        "RuntimeException" to "kotlin",
        "InterruptedException" to "kotlin",
        "InstantiationException" to "kotlin",
        "NoSuchMethodException" to "kotlin",
        "IllegalArgumentException" to "kotlin",
        "IllegalStateException" to "kotlin",
        "ClassCastException" to "kotlin",
        "Error" to "kotlin",
        "NoClassDefFoundError" to "kotlin",

        "Pair" to "kotlin",
        "Triple" to "kotlin",
        "Number" to "kotlin",

        // util²
        "JvmField" to "kotlin.jvm",
        "JvmStatic" to "kotlin.jvm",
        "Thread" to "java.lang",

        // natives
        "Boolean" to "kotlin",
        "Byte" to "kotlin",
        "Short" to "kotlin",
        "Char" to "kotlin",
        "Int" to "kotlin",
        "Long" to "kotlin",
        "Float" to "kotlin",
        "Double" to "kotlin",

        // native arrays
        "BooleanArray" to "kotlin",
        "ByteArray" to "kotlin",
        "ShortArray" to "kotlin",
        "CharArray" to "kotlin",
        "IntArray" to "kotlin",
        "LongArray" to "kotlin",
        "FloatArray" to "kotlin",
        "DoubleArray" to "kotlin",
    ).mapValues { (name, packageName) ->
        val parts = packageName.split('.')
        var currPackage = root
        for (i in parts.indices) {
            currPackage = currPackage.getOrPut(parts[i])
        }
        currPackage.getOrPut(name)
    }
}