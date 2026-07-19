package me.anno.zauber.ast.simple.expression

import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.constants.SimpleSpecialValue
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.ZClass.Companion.nativeTypes
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.CollectionType
import me.anno.zauber.types.impl.TypeUtils.canInstanceBeBoth
import me.anno.zauber.types.impl.arithmetic.AndType
import me.anno.zauber.types.impl.arithmetic.NotType
import me.anno.zauber.types.impl.arithmetic.UnionType

class SimpleInstanceOf private constructor(
    dst: SimpleField,
    val value: SimpleField,
    val type: ClassType,
    scope: Scope, origin: Long
) : SimpleAssignment(dst, scope, origin) {

    init {
        check(!type.clazz.isTypeAlias()) {
            "$this is not a simple instance-of test (${type.javaClass.simpleName}); destructure it"
        }
        check(value.type !in nativeTypes) {
            "$this can be resolved at compile time"
        }
        if (value.type is ClassType && !value.type.clazz.isOpen()) {
            LOGGER.warn("${value.type} (ClassType) can be resolved at compile time, because it is final")
        }
    }

    override fun toString(): String {
        return "$dst = $value is $type"
    }

    override fun execute(): BlockReturn? {
        val runtime = runtime
        val instance = runtime[value]
        val givenType = instance.clazz
        val expectedType = runtime.getClass(type)
        runtime[dst] = runtime.getBool(givenType.isSubTypeOf(expectedType))
        return null
    }

    override fun hasInput(field: SimpleField): Boolean = value == field

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleInstanceOf(
            src.cloned(this.dst, dst),
            src.cloned(value, dst),
            type, scope, origin
        )
    }

    companion object {
        private val LOGGER = LogManager.getLogger(SimpleInstanceOf::class)

        fun createSimpleInstanceOf(
            block: SimpleBlock,
            dst: SimpleField,
            value: SimpleField,
            type: Type,
            scope: Scope,
            origin: Long
        ): SimpleAssignment {
            val valueType = value.type
            if (!canInstanceBeBoth(valueType, type)) {
                // todo add warning about this
                LOGGER.warn("Instance cannot be $valueType and $type at once, ${resolveOrigin(origin)}")
                val expr = SimpleSpecialValue(dst, SpecialValue.FALSE, scope, origin)
                block.add(expr)
                return expr
            }
            if (isSubTypeOf(expectedType = type, actualType = valueType)) {
                // todo add warning about this
                LOGGER.warn("Instance of $valueType is always a $type, ${resolveOrigin(origin)}")
                val expr = SimpleSpecialValue(dst, SpecialValue.TRUE, scope, origin)
                block.add(expr)
                return expr
            }
            when (type) {
                is ClassType -> {
                    check(!type.clazz.isTypeAlias()) { "$type should have been resolved" }
                    val expr = SimpleInstanceOf(dst, value, type, scope, origin)
                    block.add(expr)
                    return expr
                }
                is NotType -> {
                    // just negate the result
                    val tmp = block.field(Types.Boolean)
                    createSimpleInstanceOf(block, tmp, value, type.type, scope, origin)
                    val notMethod = Types.Boolean.clazz.methods
                        .firstOrNull { it.name == "not" && it.valueParameters.isEmpty() }
                        ?: error("Missing fun Boolean.not(): Boolean")
                    val spec = Specialization.fromSimple(notMethod.scope)
                    val call = SimpleMethodCall(dst, notMethod, tmp.use(), null, spec, emptyList(), scope, origin)
                    block.add(call)
                    return call
                }
                is UnionType -> {
                    val orMethod = Types.Boolean.clazz.methods
                        .firstOrNull { it.name == "or" }
                        ?: error("Missing fun Boolean.or(other: Boolean): Boolean")
                    return reduceTypes(block, dst, value, type, scope, origin, orMethod)
                }
                is AndType -> {
                    val orMethod = Types.Boolean.clazz.methods
                        .firstOrNull { it.name == "and" }
                        ?: error("Missing fun Boolean.and(other: Boolean): Boolean")
                    return reduceTypes(block, dst, value, type, scope, origin, orMethod)
                }
                else -> throw NotImplementedError("Implement testing for ${type.javaClass.simpleName}")
            }
        }

        fun reduceTypes(
            block: SimpleBlock,
            dst: SimpleField,
            value: SimpleField,
            type: CollectionType,
            scope: Scope,
            origin: Long,

            reductionMethod: MethodLike,
        ): SimpleAssignment {
            val spec = Specialization.fromSimple(reductionMethod.scope)

            val types = type.types
            check(types.size >= 2)

            var res = block.field(Types.Boolean)
            var last = createSimpleInstanceOf(block, res, value, types.first(), scope, origin)
            block.add(last)

            for (i in 1 until types.size) {
                val tmp0 = block.field(Types.Boolean)
                val check = createSimpleInstanceOf(block, tmp0, value, types[i], scope, origin)
                block.add(check)

                val tmp1 = if (i == types.lastIndex) dst else block.field(Types.Boolean)
                last = SimpleMethodCall(
                    tmp1, reductionMethod, res.use(), null,
                    spec, listOf(tmp0.use()), scope, origin
                )
                block.add(last)
                res = tmp1
            }

            return last
        }

    }
}