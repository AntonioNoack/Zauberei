package me.anno.zauberei.types

enum class ScopeType {
    // structural
    PACKAGE,
    TYPE_ALIAS,

    // classes
    NORMAL_CLASS,
    INLINE_CLASS,
    INTERFACE,
    ENUM_CLASS,
    ENUM_ENTRY_CLASS,
    OBJECT,

    // methods
    METHOD,
    CONSTRUCTOR,
    CONSTRUCTOR_PARAMS,
    PRIMARY_CONSTRUCTOR,
    FIELD_GETTER,
    FIELD_SETTER,
    LAMBDA,
    METHOD_BODY,

    // inside expressions
    EXPRESSION,
    WHEN_CASES,
    WHEN_ELSE
}