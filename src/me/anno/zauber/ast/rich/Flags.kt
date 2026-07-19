package me.anno.zauber.ast.rich

import me.anno.zauber.ast.FlagSet
import kotlin.math.max

object Flags {

    const val NONE = 0
    const val SYNTHETIC = 1 shl 0

    /**
     * visible everywhere, default
     * */
    const val PUBLIC = 1 shl 1

    /**
     * visible in file only
     * */
    const val PRIVATE = 1 shl 2

    /**
     * not visible for top-level (packages above)
     * */
    const val PROTECTED = 1 shl 3

    /**
     * only visible within that library
     * */
    const val INTERNAL = 1 shl 4

    const val OVERRIDE = 1 shl 5
    const val OPEN = 1 shl 6
    const val FINAL = 1 shl 7
    const val ABSTRACT = 1 shl 8
    const val DATA_CLASS = 1 shl 9
    const val VALUE = 1 shl 10
    const val FUN_INTERFACE = 1 shl 11
    const val EXTERNAL = 1 shl 12
    const val OPERATOR = 1 shl 13
    const val INLINE = 1 shl 14
    const val CROSS_INLINE = 1 shl 15
    const val INFIX = 1 shl 16
    const val MACRO = 1 shl 17

    /**
     * class used for annotations;
     * is a pure value class, aka all members must be values and their properties must be, too
     * */
    const val ANNOTATION = 1 shl 18

    /**
     * class cannot be extended by classes from other packages/modules
     * */
    const val SEALED = 1 shl 19

    /**
     * evaluated at compile time
     * */
    const val CONSTEXPR = 1 shl 20

    /**
     * guaranteed to be initialized when accessed, but not before;
     * cannot be applied to nullable types in Kotlin, I'm not sure about Zauber
     * */
    const val LATEINIT = 1 shl 21

    const val CPP_STRUCT = 1 shl 22

    /**
     * a method, typically fun toTarget(): Target,
     * todo that can be used in type-resolution on other method calls
     * todo and for assignments?
     *
     * todo our ASTSimplifier must make these explicit
     * */
    const val IMPLICIT = 1 shl 23

    fun FlagSet.hasFlag(flag: FlagSet): Boolean {
        return (this and flag) == flag
    }

    fun FlagSet.hasAnyFlag(flags: FlagSet): Boolean {
        return (this and flags) != 0
    }

    fun toString(flags: FlagSet): String {
        val builder = StringBuilder()
        if (flags.hasFlag(SYNTHETIC)) builder.append("synthetic ")
        if (flags.hasFlag(PUBLIC)) builder.append("public ")
        if (flags.hasFlag(PRIVATE)) builder.append("private ")
        if (flags.hasFlag(PROTECTED)) builder.append("protected ")
        if (flags.hasFlag(IMPLICIT)) builder.append("implicit ")
        if (flags.hasFlag(OVERRIDE)) builder.append("override ")
        if (flags.hasFlag(OPEN)) builder.append("open ")
        if (flags.hasFlag(FINAL)) builder.append("final ")
        if (flags.hasFlag(ABSTRACT)) builder.append("abstract ")
        if (flags.hasFlag(DATA_CLASS)) builder.append("data ")
        if (flags.hasFlag(VALUE)) builder.append("value ")
        if (flags.hasFlag(FUN_INTERFACE)) builder.append("fun-interface ")
        if (flags.hasFlag(CONSTEXPR)) builder.append("const ")
        if (flags.hasFlag(EXTERNAL)) builder.append("external ")
        if (flags.hasFlag(OPERATOR)) builder.append("operator ")
        if (flags.hasFlag(INLINE)) builder.append("inline ")
        if (flags.hasFlag(INFIX)) builder.append("infix ")
        if (flags.hasFlag(ANNOTATION)) builder.append("annotation")
        if (flags.hasFlag(CROSS_INLINE)) builder.append("cross-inline ")
        if (flags.hasFlag(SEALED)) builder.append("sealed ")
        if (flags.hasFlag(CPP_STRUCT)) builder.append("struct ")

        builder.setLength(max(builder.length - 1, 0))
        return builder.toString()
    }
}