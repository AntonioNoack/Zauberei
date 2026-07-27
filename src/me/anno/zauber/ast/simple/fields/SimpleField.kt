package me.anno.zauber.ast.simple.fields

import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.YELLOW
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.simple.SimpleMerge
import me.anno.zauber.ast.simple.expression.SimpleAssignment
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.unresolved.*

/**
 * LLVM-style field, which is assigned exactly once. SimpleMergeInfo joins fields where necessary.
 * For languages, where you can assign to such a field from multiple locations, use .dst instead and ignore SimpleMergeInfo.
 * */
class SimpleField(
    val type: Type, var id: Int,
    val constantRef: Expression?
) {

    init {
        when (type) {
            is UnresolvedClassType,
            is UnresolvedSubType,
            is UnresolvedAndType,
            is UnresolvedUnionType,
            is UnresolvedNotType -> error("$this cannot be unresolved")
            is ClassType -> {
                if (type.clazz.isTypeAlias()) {
                    error("$this cannot be unresolved (a type-alias)")
                }
            }
        }
    }

    var numReads = 0
    var mergeInfo: SimpleMerge? = null

    /**
     * flag for nicer code generation:
     * if this is set, we can inline the value
     * */
    var immediateValue: SimpleAssignment? = null

    /**
     * todo use this, 0 = this, 1 = self, 2 = unit, 3+x = parameters
     * */
    var fromLocalField: LocalField? = null

    fun use(): SimpleField {
        numReads++
        return this
    }

    val dst: SimpleField
        get() {
            var dst = this
            while (true) {
                dst = dst.next
                    ?: return dst
            }
        }

    val next: SimpleField?
        get() = mergeInfo?.dst

    override fun toString(): String {
        val idName = style("%$id", YELLOW)
        return when {
            constantRef is NumberExpression ->
                "${style("\"${constantRef.value}\"", StringStyles.DARK_BLUE)}$idName"
            constantRef is StringExpression ->
                "${style("\"${constantRef.value}\"", StringStyles.GREEN)}$idName"
            constantRef is SpecialValueExpression ->
                "${style("${constantRef.type}", StringStyles.DARK_BLUE)}$idName"
            constantRef != null -> "\"$constantRef\"$idName"
            type is ClassType && type.clazz.isObjectLike() ->
                "[${type.clazz}]$idName"
            else -> "$idName[$type]"
        } + if (mergeInfo != null) "->${style("%${dst.id}", YELLOW)}" else ""
    }

}