package me.anno.zauberei.types

import me.anno.zauberei.Compile.root
import me.anno.zauberei.Compile.stdlib

/**
 * types, that are automatically imported into every file
 * */
object StandardTypes {

    init {
        // stdlib must be a package to be searched for children automatically
        root.getOrPut(stdlib, ScopeType.PACKAGE)
    }

    val standardClasses = mapOf(
        // strings
        "String" to stdlib,
        "StringBuilder" to stdlib,
        "CharSequence" to stdlib,

        // special types
        "Any" to stdlib,
        "Nothing" to stdlib,
        "Unit" to stdlib,
        "Array" to stdlib,

        // util
        "Class" to stdlib,
        "Enum" to stdlib,
        "IntRange" to stdlib,
        "ClosedFloatingPointRange" to stdlib,
        "Lazy" to stdlib,

        "Comparable" to stdlib,
        "Comparator" to stdlib,

        "Iterator" to stdlib,
        "ListIterator" to stdlib,
        "MutableIterator" to stdlib,
        "MutableListIterator" to stdlib,
        "Iterable" to stdlib,
        "Collection" to stdlib,
        "MutableCollection" to stdlib,

        "List" to stdlib,
        "ArrayList" to stdlib,
        "MutableList" to stdlib,

        "IndexedValue" to stdlib,

        "Set" to stdlib,
        "HashSet" to stdlib,
        "MutableSet" to stdlib,

        "Map" to stdlib,
        "HashMap" to stdlib,
        "MutableMap" to stdlib,

        "Annotation" to stdlib,
        "Suppress" to stdlib,
        "Deprecated" to stdlib,

        "Throwable" to stdlib,
        "Exception" to stdlib,
        "RuntimeException" to stdlib,
        "InterruptedException" to stdlib,
        "InstantiationException" to stdlib,
        "NoSuchMethodException" to stdlib,
        "IllegalArgumentException" to stdlib,
        "IllegalStateException" to stdlib,
        "ClassCastException" to stdlib,
        "Error" to stdlib,
        "NoClassDefFoundError" to stdlib,
        "ClassNotFoundException" to stdlib,
        "NoSuchFieldException" to stdlib,
        "NoSuchMethodException" to stdlib,
        "OutOfMemoryError" to stdlib,
        "IndexOutOfBoundsException" to stdlib,

        "Pair" to stdlib,
        "Triple" to stdlib,
        "Number" to stdlib,

        // util²
        "JvmField" to "kotlin.jvm",
        "JvmStatic" to "kotlin.jvm",
        "JvmOverloads" to "kotlin.jvm",
        "Throws" to "kotlin.jvm",
        "Thread" to "java.lang",
        "ThreadLocal" to "java.lang",
        "Process" to "java.lang",
        "ClassLoader" to "java.lang",
        "AbstractList" to "java.util",
        "RandomAccess" to "java.util",

        // natives
        "Boolean" to stdlib,
        "Byte" to stdlib,
        "Short" to stdlib,
        "Char" to stdlib,
        "Int" to stdlib,
        "Long" to stdlib,
        "Float" to stdlib,
        "Double" to stdlib,

        // native arrays
        "BooleanArray" to stdlib,
        "ByteArray" to stdlib,
        "ShortArray" to stdlib,
        "CharArray" to stdlib,
        "IntArray" to stdlib,
        "LongArray" to stdlib,
        "FloatArray" to stdlib,
        "DoubleArray" to stdlib,
    ).mapValues { (name, packageName) ->
        val parts = packageName.split('.')
        var currPackage = root
        for (i in parts.indices) {
            currPackage = currPackage.getOrPut(parts[i], null)
        }
        currPackage.getOrPut(name, null)
    }

    init {
        // mark these types as not having a generic parameter
        val nonGenericTypes = listOf(
            // strings
            "String",
            "StringBuilder",
            "CharSequence",

            // special types
            "Any",
            "Nothing",
            "Unit",

            // util
            "IntRange",
            "ClosedFloatingPointRange",

            "Annotation",
            "Suppress",
            "Deprecated",

            "Throwable",
            "Exception",
            "RuntimeException",
            "InterruptedException",
            "InstantiationException",
            "NoSuchMethodException",
            "IllegalArgumentException",
            "IllegalStateException",
            "ClassCastException",
            "Error",
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "NoSuchFieldException",
            "NoSuchMethodException",
            "OutOfMemoryError",
            "IndexOutOfBoundsException",

            "Number",

            // util²
            "JvmField",
            "JvmStatic",
            "JvmOverloads",
            "Throws",
            "Thread",
            "Process",
            "ClassLoader",

            // natives
            "Boolean",
            "Byte",
            "Short",
            "Char",
            "Int",
            "Long",
            "Float",
            "Double",

            // native arrays
            "BooleanArray",
            "ByteArray",
            "ShortArray",
            "CharArray",
            "IntArray",
            "LongArray",
            "FloatArray",
            "DoubleArray",
        )
        for (name in nonGenericTypes) {
            val type = standardClasses[name]!!
            type.hasTypeParameters = true
        }
    }
}
