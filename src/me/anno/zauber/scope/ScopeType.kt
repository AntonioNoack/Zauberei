package me.anno.zauber.scope

import me.anno.utils.StringStyles.DARK_BLUE
import me.anno.utils.StringStyles.style

enum class ScopeType {
    // structural
    PACKAGE,
    TYPE_ALIAS,

    // classes
    NORMAL_CLASS,
    INLINE_CLASS,
    INNER_CLASS,

    INTERFACE,
    ENUM_CLASS,  // limited instances, val name: String, val ordinal: Int, fun entries()
    ENUM_ENTRY, // objects with enum_class as type

    OBJECT,
    COMPANION_OBJECT,

    /**
     * inside methods: for yields
     * inside classes: to change value/ref classes
     * */
    VIRTUAL_CLASS,

    // methods
    /**
     * definition space for method arguments
     * */
    METHOD,

    /**
     * definition space for constructor arguments
     * */
    CONSTRUCTOR,

    FIELD_GETTER,
    FIELD_SETTER,
    LAMBDA,

    // just a virtual scope, because fields can be moved
    FIELD,

    // inside expressions
    METHOD_BODY,
    MACRO,
    WHEN_CASES,
    WHEN_ELSE,
    ;

    fun isClass(): Boolean {
        return when (this) {
            NORMAL_CLASS, ENUM_CLASS, INNER_CLASS, INLINE_CLASS,
            INTERFACE, VIRTUAL_CLASS -> true
            else -> false
        }
    }

    fun isClassLike(): Boolean {
        return isClass() || isObject()
    }

    fun isObject(): Boolean {
        return when (this) {
            OBJECT, COMPANION_OBJECT -> true
            else -> false
        }
    }

    fun isObjectLike(): Boolean {
        return isObject() || this == PACKAGE
    }

    fun isInsideExpression(): Boolean {
        return when (this) {
            FIELD_GETTER,
            FIELD_SETTER,
            LAMBDA,
            METHOD_BODY,
            WHEN_CASES,
            WHEN_ELSE,
            MACRO -> true
            else -> false
        }
    }

    fun needsTypeParams(): Boolean {
        if (isInsideExpression()) return false
        if (this == INLINE_CLASS || this == PACKAGE) return false
        return true
    }

    fun isMethod(): Boolean {
        return when (this) {
            METHOD, LAMBDA,
            FIELD_GETTER, FIELD_SETTER -> true
            else -> false
        }
    }

    fun isMethodLike(): Boolean {
        return isMethod() || isConstructor()
    }

    fun isConstructor(): Boolean = this == CONSTRUCTOR

    override fun toString(): String {
        return style(name, DARK_BLUE)
    }
}