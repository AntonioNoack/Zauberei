package me.anno.zauber.interpreting

import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.bold
import me.anno.utils.StringStyles.style
import me.anno.zauber.SpecialFieldNames.OUTER_FIELD_NAME
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.RuntimeCreate.createInt
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.Inheritance
import me.anno.zauber.typeresolution.InsertMode
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.arithmetic.NullType

class ZClass(val type: Type) {

    companion object {

        // types, which may contain the 'content' field
        // to differentiate instance and native field
        val nativeTypes by threadLocal {
            listOf(
                Types.Byte, Types.UByte,
                Types.UShort, Types.UShort, Types.Char,
                Types.Int, Types.UInt,
                Types.Long, Types.ULong,
                Types.Half, Types.Float, Types.Double
            )
        }

        fun collectFields(type: ClassType): List<Field> {
            if (type in nativeTypes) {
                return emptyList()
            }
            val fields = type.clazz[ScopeInitType.AFTER_OVERRIDES]
                .fields.filter { field -> field.needsToBeStored() }
            if (type.clazz.isConstructor() && type.clazz.parent!!.scopeType == ScopeType.INNER_CLASS) {
                // move __outer__ to the front
                // todo ideally, it would be sorted inside the scope already...
                return fields.sortedByDescending { it.name == OUTER_FIELD_NAME }
            }
            // println("fields for $type: $fields")
            return fields
        }

        fun Field.needsToBeStored(): Boolean {
            if (isObjectInstance()) return false

            val type = scope.typeWithoutArgs
            // LOGGER.info("[$type0] $this needs backing field? (${!explicitSelfType} || $selfType == $type) && ${needsBackingField()}")
            return (!hasExplicitSelfType || selfType == type) &&
                    needsBackingField()
        }
    }

    val fields = if (type is ClassType) collectFields(type) else emptyList()

    private var objectInstance: Instance? = null

    // todo bug: this is currently used inside methods,
    //  so if a recursive function called itself,
    //  we would have only one instance

    fun getOrCreateObjectInstance(): Instance {
        var instance = objectInstance
        if (instance != null) return instance

        if (type !is ClassType) {
            error("Cannot create object instance for $type")
        }

        val scope = type.clazz[ScopeInitType.AFTER_DISCOVERY]
        when (scope.scopeType) {
            ScopeType.PACKAGE, ScopeType.OBJECT, ScopeType.COMPANION_OBJECT -> {} // ok
            else -> error("Cannot create object instance for $scope (${scope.scopeType})")
        }

        instance = createInstance()

        // first must be assigned, then we can initialize it,
        // otherwise we would run into recursive issues, because
        // our runtime does not "simplify" SimpleGetObject to "this"
        objectInstance = instance

        callPrimaryConstructor(instance)

        return instance
    }

    fun createInstance(): Instance {
        if (type == NullType || type == Types.Nothing) {
            error("Cannot create instance of $type")
        }
        if (type !is ClassType) {
            error("Type to create must be concrete and fully specified ($type)")
        }
        return Instance(this, arrayOfNulls(fields.size), runtime.nextInstanceId())
    }

    fun callPrimaryConstructor(instance: Instance) {
        val scope = (type as ClassType).clazz[ScopeInitType.AFTER_DISCOVERY]
        val primaryConstructor = scope.primaryConstructorScope?.selfAsConstructor ?: return

        check(primaryConstructor.valueParameters.isEmpty()) {
            "Primary $scope constructur must not have valueParameters"
        }

        val spec = Specialization(type)
            .withScope(primaryConstructor.memberScope)
        runtime.executeCall(
            instance, null,
            spec, emptyList(), -1
        )
    }

    fun createArray(items: Array<Instance>): Instance {
        val rt = runtime
        val clazz = rt.getClass(Types.Array.withTypeParameter(type))
        val result = clazz.createInstance()
        result["size"] = rt.createInt(items.size)
        result.rawValue = items
        return result
    }

    fun isSubTypeOf(expectedType: ZClass): Boolean {
        return Inheritance.isSubTypeOf(
            expectedType.type,
            type, emptyList(), ParameterList.emptyParameterList(),
            InsertMode.READ_ONLY
        )
    }

    val isValueClass: Boolean =
        type is ClassType && type.clazz.flags.hasFlag(Flags.VALUE)

    override fun toString(): String {
        return "${bold("ZClass")}($type," +
                "[${fields.joinToString(",") { field -> style("\"${field.name}\"", GREEN) }}])"
    }

}