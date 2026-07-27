package me.anno.zauber.ast.simple.fields

import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization

class SimpleSetClassField(
    override val self: SimpleField,
    override val field: Field,
    var value: SimpleField,
    override val specialization: Specialization,
    scope: Scope, origin: Long
) : SimpleInstruction(scope, origin), SimpleGSetClassField {

    companion object {
        private val LOGGER = LogManager.getLogger(SimpleSetClassField::class)
    }

    init {
        check(specialization.scope == field.fieldScope) {
            "Expected specialization to belong to $field (${field.fieldScope}), but got ${specialization.scope}"
        }
        check(!field.ownerScope.isInterface()) {
            "Cannot just set field of an interface, must use getter, $field"
        }
        check(!field.isBackingField()) {
            "Cannot get 'backing' field $field in ${field.ownerScope}, needs to use direct access"
        }
    }

    override fun hasInput(field: SimpleField): Boolean {
        return self == field || value == field
    }

    override fun toString(): String {
        return "$self?[${field.selfType}]." +
                "${style(field.name, StringStyles.GREEN)} = $value"
    }

    override fun withField(field: Field): SimpleInstruction {
        val spec = Specialization(field.fieldScope, specialization.typeParameters)
        return SimpleSetClassField(self, field, value, spec, scope, origin)
    }

    override fun execute(): BlockReturn? {
        val runtime = runtime
        val selfInstance = runtime[self]
        val value = runtime[value]
        if (LOGGER.isInfoEnabled) LOGGER.info("[SET] $selfInstance.${field.name} (${field.ownerScope}) = $value")
        runtime[selfInstance, field] = value.cloneIfValue()
        return null
    }

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleSetClassField(
            src.cloned(this.self, dst), field,
            src.cloned(value, dst),
            specialization, scope, origin
        )
    }

}