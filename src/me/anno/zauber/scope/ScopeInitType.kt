package me.anno.zauber.scope

enum class ScopeInitType {

    DISCOVER_MEMBERS,

    /**
     * after discovering all class members, so all DECLARED fields and methods should be available
     * */
    AFTER_DISCOVERY,

    /**
     * later stages rely on types again, and again: resolve types in this stage
     * */
    RESOLVE_TYPES,
    /**
     * all methods and fields should have their returnType and valueType defined
     * */
    AFTER_RESOLVE_TYPES,

    /**
     * register all implicit conversion methods
     * */
    CONVERSION_METHODS,
    AFTER_CONVERSION_METHODS,

    /**
     * split methods with default parameters into variants
     * */
    DEFAULT_PARAMETERS,
    ADD_OVERRIDES,

    /**
     * after copying methods and fields from super-types, so all available fields and methods should be present
     * */
    AFTER_OVERRIDES,

    RESOLVE_METHOD_BODY,
    AFTER_RESOLVE_METHOD_BODY,

    CODE_GENERATION,

    ;

    fun next() = entries[ordinal + 1]
}