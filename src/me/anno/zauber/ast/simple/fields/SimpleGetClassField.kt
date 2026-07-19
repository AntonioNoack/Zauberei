package me.anno.zauber.ast.simple.fields

import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleAssignment
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.impl.ClassType

class SimpleGetClassField(
    dst: SimpleField,
    override val self: SimpleField,
    override val field: Field,
    override val specialization: Specialization,
    scope: Scope, origin: Long
) : SimpleAssignment(dst, scope, origin), SimpleGSetClassField {

    init {
        check(specialization.scope == field.fieldScope) {
            "Field must match specialization scope, ${specialization.scope} != ${field.fieldScope}"
        }

        if (self.type is ClassType) {
            val selfScope = self.type.clazz
            val fieldScope = field.ownerScope
            if (selfScope.isInnerClassOf(fieldScope) ||
                fieldScope.isInnerClassOf(selfScope)
            ) {
                error("Cannot get $field from $self")
            }
        }
        check(!field.ownerScope.isInterface()) {
            "Cannot just get field of an interface, must use getter, $field"
        }
        check(!field.isBackingField()) {
            "Cannot get 'backing' field $field in ${field.ownerScope}, needs to use direct access"
        }

        if (field.ownerScope.isCompanionObject() &&
            self.type is ClassType &&
            !self.type.clazz.isCompanionObject()
        ) {
            error("Invalid field access, self must be companion object: $this")
        }
    }

    override fun toString(): String {
        return "$dst = $self?[${field.selfType ?: field.ownerScope}]." +
                style(field.name, StringStyles.GREEN)
    }

    override fun hasInput(field: SimpleField): Boolean {
        return self == field
    }

    override fun withField(field: Field): SimpleInstruction {
        val spec = Specialization(field.fieldScope, specialization.typeParameters)
        return SimpleGetClassField(dst, self, field, spec, scope, origin)
    }

    override fun execute(): BlockReturn? {
        val runtime = runtime
        val self = runtime[self]
        runtime[dst] = self[field]
        return null
    }

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleGetClassField(
            src.cloned(this.dst, dst),
            src.cloned(self, dst),
            field, specialization, scope, origin
        )
    }

}