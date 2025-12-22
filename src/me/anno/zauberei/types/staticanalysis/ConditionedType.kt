package me.anno.zauberei.types.staticanalysis

import me.anno.zauberei.types.Type

/**
 * todo for static code analysis, we will want these types everywhere...
 * */
class ConditionedType(val base: Type, val conditions: List<Condition>): Type() {

    class Condition(val type: ConditionedType, val reference: Any?)

    enum class ConditionType {
        EQUALS_CONSTANT,
        NOT_EQUALS_CONSTANT,

        INSTANCEOF,
        NOT_INSTANCEOF,

        GREATER_THAN,
        GREATER_THAN_OR_EQUALS,
        LESS_THAN,
        LESS_THAN_OR_EQUALS,

        PREDICATE_FUNCTION
    }
}